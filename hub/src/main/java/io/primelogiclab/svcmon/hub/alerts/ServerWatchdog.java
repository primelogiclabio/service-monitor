package io.primelogiclab.svcmon.hub.alerts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.ServerInfo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices when an agent goes silent or comes back. When an agent dies, its checks stop reporting, so no check ever
 * changes status; without this, the most common failure would raise no alert at all.
 * The first scan only learns who is online (so a hub restart does not alert about servers that were already down).
 */
@Component
class ServerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ServerWatchdog.class);

    private final ServerRepo servers;
    private final HubProperties props;
    private final ApplicationEventPublisher events;
    private final Map<String, Boolean> online = new HashMap<>();

    ServerWatchdog(ServerRepo servers, HubProperties props, ApplicationEventPublisher events) {
        this.servers = servers;
        this.props = props;
        this.events = events;
    }

    @Scheduled(initialDelayString = "${svcmon.watchdog-initial-delay-ms:30000}",
            fixedDelayString = "${svcmon.watchdog-interval-ms:10000}")
    void scan() {
        try {
            long now = System.currentTimeMillis();
            long staleMs = props.agentStaleSeconds() * 1000L;
            Set<String> seen = new HashSet<>();
            for (ServerInfo s : servers.findAll()) {
                seen.add(s.id());
                boolean up = s.lastSeen() != null && now - s.lastSeen() <= staleMs;
                Boolean was = online.put(s.id(), up);
                if (was != null && was != up) {
                    String message = up
                            ? "The agent is reporting again"
                            : "No report from the agent for " + (s.lastSeen() == null ? "a while" : (now - s.lastSeen()) / 1000 + "s")
                            + " (limit " + props.agentStaleSeconds() + "s)";
                    events.publishEvent(new ServerStatusEvent(s, up, message, now));
                }
            }
            online.keySet().retainAll(seen);
        } catch (RuntimeException e) {
            log.warn("Server watchdog scan failed: {}", e.getMessage());
        }
    }
}
