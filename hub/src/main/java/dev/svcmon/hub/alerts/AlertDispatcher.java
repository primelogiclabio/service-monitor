package dev.svcmon.hub.alerts;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.svcmon.hub.core.StatusChangedEvent;
import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.AlertType;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.ServerInfo;
import dev.svcmon.hub.model.Status;
import dev.svcmon.hub.security.HubIdentity;
import dev.svcmon.hub.store.AlertChannelRepo;
import dev.svcmon.hub.store.ServerRepo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for status changes and sends them to every enabled channel that wants that status. Each delivery runs on
 * its own virtual thread, so a slow or broken receiver never delays checks or other channels, and is retried a few
 * times when the failure looks temporary.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private record Outcome(String error, boolean retryable) {
        static final Outcome OK = new Outcome(null, false);
    }

    private final AlertChannelRepo channels;
    private final ServerRepo servers;
    private final HubIdentity hub;
    private final Map<AlertType, AlertSender> senders = new EnumMap<>(AlertType.class);
    private final long[] retryDelaysMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    AlertDispatcher(AlertChannelRepo channels, ServerRepo servers, HubIdentity hub, List<AlertSender> all,
                    @Value("${svcmon.alert-retry-delays-ms:0,2000,5000}") long[] retryDelaysMs) {
        this.channels = channels;
        this.servers = servers;
        this.hub = hub;
        this.retryDelaysMs = retryDelaysMs.length == 0 ? new long[] {0} : retryDelaysMs;
        for (AlertSender s : all) {
            senders.put(s.type(), s);
        }
    }

    @EventListener
    public void onCheckChange(StatusChangedEvent e) {
        CheckDef c = e.check();
        String serverName = c.serverId() == null ? null : servers.find(c.serverId()).map(ServerInfo::name).orElse(null);
        dispatch(new AlertEvent(AlertEvent.Kind.CHECK, c.id(), c.name(), c.type().name(), c.target(),
                c.serverId(), serverName, e.from(), e.to(), e.message(), e.ts()));
    }

    @EventListener
    public void onServerChange(ServerStatusEvent e) {
        ServerInfo s = e.server();
        dispatch(new AlertEvent(AlertEvent.Kind.SERVER, s.id(), "agent", "AGENT", null, s.id(), s.name(),
                e.online() ? Status.DOWN : Status.UP, e.online() ? Status.UP : Status.DOWN, e.message(), e.ts()));
    }

    /** Sends the event to every enabled channel whose statuses include the new status. Returns immediately. */
    public void dispatch(AlertEvent event) {
        List<AlertChannel> targets;
        try {
            targets = channels.findEnabled();
        } catch (RuntimeException ex) {
            log.warn("Could not load alert channels: {}", ex.getMessage());
            return;
        }
        for (AlertChannel channel : targets) {
            if (channel.statuses().contains(event.to())) {
                executor.submit(() -> deliver(channel, event));
            }
        }
    }

    private void deliver(AlertChannel channel, AlertEvent event) {
        Outcome outcome = Outcome.OK;
        for (int attempt = 0; attempt < retryDelaysMs.length; attempt++) {
            if (retryDelaysMs[attempt] > 0) {
                try {
                    Thread.sleep(retryDelaysMs[attempt]);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            outcome = tryOnce(channel, event);
            if (outcome.error() == null || !outcome.retryable()) {
                break;
            }
        }
        record(channel, outcome);
    }

    /** One attempt, no retries, result remembered on the channel. Used by the "test" endpoint. Returns an error or null. */
    public String deliverOnce(AlertChannel channel, AlertEvent event) {
        Outcome outcome = tryOnce(channel, event);
        record(channel, outcome);
        return outcome.error();
    }

    private Outcome tryOnce(AlertChannel channel, AlertEvent event) {
        AlertSender sender = senders.get(channel.type());
        if (sender == null) {
            return new Outcome("No sender for " + channel.type(), false);
        }
        try {
            sender.send(channel, event, hub.id());
            return Outcome.OK;
        } catch (AlertDeliveryException e) {
            return new Outcome(e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            return new Outcome("Unexpected error: " + e.getClass().getSimpleName(), false);
        }
    }

    private void record(AlertChannel channel, Outcome outcome) {
        try {
            channels.recordAttempt(channel.id(), System.currentTimeMillis(), outcome.error() == null, outcome.error());
        } catch (RuntimeException e) {
            log.debug("Could not record alert outcome for {}: {}", channel.name(), e.getMessage());
        }
        if (outcome.error() != null) {
            log.warn("Alert channel '{}' ({}) failed: {}", channel.name(), channel.type(), outcome.error());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
