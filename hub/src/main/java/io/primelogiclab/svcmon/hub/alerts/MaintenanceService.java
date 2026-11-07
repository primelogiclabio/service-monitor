package io.primelogiclab.svcmon.hub.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.audit.AuditService.Actor;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.core.StatusViews.MaintenanceView;
import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckState;
import io.primelogiclab.svcmon.hub.model.Maintenance;
import io.primelogiclab.svcmon.hub.model.ServerInfo;
import io.primelogiclab.svcmon.hub.model.Status;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import io.primelogiclab.svcmon.hub.store.MaintenanceRepo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import io.primelogiclab.svcmon.hub.store.StateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintenance windows: while a server's window is open, {@link AlertDispatcher} sends nothing about its agent or its
 * checks. A window snapshots how things stood when it opened and, when it ends, sends one alert for each thing that is
 * not back to that, so a server that never comes back from its restart still alerts, once.
 * <p>
 * A window ends at its time limit, when someone ends it, or (unless asked to run its full time) as soon as everything
 * is back: something went down while it was open, the agent is reporting again, and every enabled check has run since
 * and is UP (or as it was before, for one that was already down). One that nothing ever disturbed runs to its limit.
 * <p>
 * Opening, ending and muting take the same lock, and ending deletes the window before it reads how things stand, so a
 * change is either muted and seen by the end, or sent. A change that lands between its check's state being stored and
 * the event can at worst be alerted twice, never missed.
 */
@Service
public class MaintenanceService {

    /** The longest window an admin may open, in minutes (a day). */
    public static final int ADMIN_MAX_MINUTES = 1440;
    static final int MAX_REASON = 200;
    static final String AFTER = "After maintenance: ";

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    /** What opens a window: how long, why, and whether it may end early. */
    public record MaintenanceRequest(Integer minutes, String reason, Boolean untilBack) {
    }

    private final MaintenanceRepo windows;
    private final ServerRepo servers;
    private final CheckRepo checks;
    private final StateRepo states;
    private final HubProperties props;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ServerWatchdog watchdog;

    MaintenanceService(MaintenanceRepo windows, ServerRepo servers, CheckRepo checks, StateRepo states,
                       HubProperties props, AuditService audit, ApplicationEventPublisher events,
                       ServerWatchdog watchdog) {
        HubProperties.Maintenance cfg = settings(props);
        if (cfg.agentMaxMinutes() < 1 || cfg.agentMaxMinutes() > ADMIN_MAX_MINUTES) {
            throw new IllegalStateException("svcmon.maintenance.agent-max-minutes must be between 1 and " + ADMIN_MAX_MINUTES);
        }
        this.windows = windows;
        this.servers = servers;
        this.checks = checks;
        this.states = states;
        this.props = props;
        this.audit = audit;
        this.events = events;
        this.watchdog = watchdog;
    }

    private static HubProperties.Maintenance settings(HubProperties props) {
        return props.maintenance() == null ? HubProperties.Maintenance.DEFAULT : props.maintenance();
    }

    // ---- opening and ending ----

    /** An admin opens (or extends) a server's window. */
    public MaintenanceView open(String serverId, MaintenanceRequest req, Actor actor) {
        ServerInfo server = servers.find(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        return open(server, req, ADMIN_MAX_MINUTES, actor);
    }

    /** An agent opens (or extends) its own server's window, if the hub lets agents do so. */
    public MaintenanceView openForAgent(TokenRecord agent, MaintenanceRequest req, String address) {
        if (!settings(props).agentAllowed()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "agent_maintenance_off",
                    "Agents may not open maintenance windows on this hub (svcmon.maintenance.agent-allowed)");
        }
        ServerInfo server = servers.find(agent.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        MaintenanceView view = open(server, req, settings(props).agentMaxMinutes(), Actor.agent(server.name()));
        audit.change("maintenance_started", "ok", Actor.agent(server.name()), address,
                "POST /api/v1/agent/maintenance", server.name(), describe(req));
        return view;
    }

    /** How a request reads in the audit: its length and way of ending, never its reason (free text from outside). */
    public static String describe(MaintenanceRequest req) {
        boolean untilBack = req.untilBack() == null || req.untilBack();
        return req.minutes() + " min" + (untilBack ? ", ends early once everything is back" : ", full time");
    }

    private synchronized MaintenanceView open(ServerInfo server, MaintenanceRequest req, int maxMinutes, Actor actor) {
        if (req == null || req.minutes() == null) {
            throw ApiException.badRequest("minutes is required");
        }
        if (req.minutes() < 1 || req.minutes() > maxMinutes) {
            throw ApiException.badRequest("minutes must be between 1 and " + maxMinutes);
        }
        String reason = reason(req.reason());
        boolean untilBack = req.untilBack() == null || req.untilBack();
        long now = System.currentTimeMillis();
        long endsAt = now + req.minutes() * 60_000L;

        Optional<Maintenance> open = windows.find(server.id());
        if (open.isPresent()) {
            // Opening again never brings the end closer: an agent's short window must not cut an admin's long one.
            windows.extend(server.id(), Math.max(endsAt, open.get().endsAt()), reason, untilBack);
        } else {
            windows.insert(new Maintenance(server.id(), now, endsAt, reason, actor.name(), actor.kind(), untilBack,
                    snapshot(server, now), null));
            log.info("Maintenance of {} opened by {} {} for {} min: its alerts are held back", server.name(),
                    actor.kind(), actor.name(), req.minutes());
        }
        return MaintenanceView.of(windows.find(server.id()).orElseThrow());
    }

    private static String reason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String r = raw.strip();
        if (r.length() > MAX_REASON) {
            throw ApiException.badRequest("reason must be at most " + MAX_REASON + " characters");
        }
        if (r.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) {
            throw ApiException.badRequest("reason must be one line of text");
        }
        return r;
    }

    /** An admin ends a server's window now. Returns false when none was open. */
    public boolean end(String serverId, Actor actor) {
        servers.find(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        return close(serverId, "ended by " + actor.kind() + " " + actor.name());
    }

    /** An agent ends its own server's window; always allowed, since ending only lets alerts out again. */
    public boolean endForAgent(TokenRecord agent, String address) {
        ServerInfo server = servers.find(agent.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        boolean ended = close(server.id(), "ended by its agent");
        if (ended) {
            audit.change("maintenance_ended", "ok", Actor.agent(server.name()), address,
                    "DELETE /api/v1/agent/maintenance", server.name(), null);
        }
        return ended;
    }

    private synchronized boolean close(String serverId, String why) {
        Optional<Maintenance> open = windows.find(serverId);
        if (open.isEmpty() || !windows.delete(serverId)) {
            return false;
        }
        Optional<ServerInfo> server = servers.find(serverId);
        List<AlertEvent> notBack = server.map(s -> notBack(s, open.get(), System.currentTimeMillis())).orElse(List.of());
        log.info("Maintenance of {} ended ({}); {} alert(s) for what is not as it was", server.map(ServerInfo::name)
                .orElse(serverId), why, notBack.size());
        if (!notBack.isEmpty()) {
            events.publishEvent(new MaintenanceEnded(notBack));
        }
        return true;
    }

    // ---- muting ----

    /**
     * Whether an alert about this server must be held back. A change to anything but UP while the window is open
     * counts as something going down, which an early end then waits to see come back.
     */
    synchronized boolean mutes(String serverId, Status to) {
        if (serverId == null || windows.find(serverId).isEmpty()) {
            return false;
        }
        if (to != Status.UP) {
            windows.markDisturbed(serverId, System.currentTimeMillis());
        }
        return true;
    }

    // ---- the clock ----

    @Scheduled(initialDelayString = "${svcmon.watchdog-initial-delay-ms:30000}",
            fixedDelayString = "${svcmon.watchdog-interval-ms:10000}")
    void tick() {
        tickAt(System.currentTimeMillis());
    }

    /** One review of every open window as of {@code now}; tests pass a later time to reach a window's limit. */
    void tickAt(long now) {
        try {
            for (Maintenance m : windows.findAll()) {
                review(m, now);
            }
        } catch (RuntimeException e) {
            log.warn("Maintenance review failed: {}", e.getMessage());
        }
    }

    private synchronized void review(Maintenance m, long now) {
        if (now >= m.endsAt()) {
            if (close(m.serverId(), "time limit")) {
                audit.change("maintenance_ended", "ok", Actor.HUB, null, null, serverName(m.serverId()), "time limit");
            }
            return;
        }
        ServerInfo server = servers.find(m.serverId()).orElse(null);
        if (server == null) {
            return;
        }
        if (away(server, now)) {
            windows.markDisturbed(server.id(), now);
            return;
        }
        Maintenance fresh = windows.find(server.id()).orElse(null);
        if (fresh != null && fresh.untilBack() && fresh.disturbedAt() != null && allBack(server, fresh)) {
            if (close(server.id(), "everything is back")) {
                audit.change("maintenance_ended", "ok", Actor.HUB, null, null, server.name(), "everything is back");
            }
        }
    }

    private String serverName(String id) {
        return servers.find(id).map(ServerInfo::name).orElse(id);
    }

    /**
     * Whether the agent has been quiet for longer than two heartbeats (or than the offline limit, if shorter): the sign
     * of a restart that is quicker than the offline limit, so the watchdog never sees it.
     */
    private boolean away(ServerInfo s, long now) {
        long awayMs = Math.min(2L * props.agentHeartbeatSeconds(), props.agentStaleSeconds()) * 1000L;
        return s.lastSeen() == null || now - s.lastSeen() > awayMs;
    }

    /**
     * The agent's status as the offline watchdog last saw it (by the time of its last report before the watchdog's
     * first look), so an alert sent as a window ends is followed by the watchdog's next change, not contradicted by it.
     */
    private Status agentStatus(ServerInfo s, long now) {
        Boolean seen = watchdog.seenOnline(s.id());
        boolean up = seen != null ? seen : s.lastSeen() != null && now - s.lastSeen() <= props.agentStaleSeconds() * 1000L;
        return up ? Status.UP : Status.DOWN;
    }

    private boolean allBack(ServerInfo server, Maintenance m) {
        long since = m.disturbedAt();
        // Reporting since the last disturbance, and the watchdog has caught up: otherwise its "reporting again" would
        // go out after the window ended.
        if (server.lastSeen() == null || server.lastSeen() <= since
                || !Boolean.TRUE.equals(watchdog.seenOnline(server.id()))) {
            return false;
        }
        for (CheckDef c : checks.findByServer(server.id())) {
            if (!c.enabled()) {
                continue;
            }
            Status before = m.snapshot().checks().getOrDefault(c.id(), Status.UNKNOWN);
            CheckState now = states.find(c.id()).orElse(null);
            if (now == null) {
                if (before == Status.UNKNOWN) {
                    continue; // has never run: nothing to wait for
                }
                return false;
            }
            boolean ranSince = now.lastResultAt() != null && now.lastResultAt() > since;
            boolean up = now.status() == Status.UP && now.consecutiveFailures() == 0;
            boolean asBefore = before != Status.UP && before != Status.UNKNOWN && now.status() == before;
            if (!ranSince || !(up || asBefore)) {
                return false;
            }
        }
        return true;
    }

    // ---- snapshots ----

    private Maintenance.Snapshot snapshot(ServerInfo server, long now) {
        Map<String, Status> byCheck = new LinkedHashMap<>();
        for (CheckDef c : checks.findByServer(server.id())) {
            if (c.enabled()) {
                byCheck.put(c.id(), states.find(c.id()).map(CheckState::status).orElse(Status.UNKNOWN));
            }
        }
        return new Maintenance.Snapshot(agentStatus(server, now), byCheck);
    }

    /** One alert for each thing whose status differs from when the window opened. */
    private List<AlertEvent> notBack(ServerInfo s, Maintenance m, long now) {
        List<AlertEvent> out = new ArrayList<>();
        Status agentWas = m.snapshot().agent();
        Status agentNow = agentStatus(s, now);
        if (agentNow != agentWas) {
            String message = agentNow == Status.UP ? "the agent is reporting again"
                    : "no report from the agent for " + (s.lastSeen() == null ? "a while" : (now - s.lastSeen()) / 1000 + "s");
            out.add(new AlertEvent(AlertEvent.Kind.SERVER, s.id(), "agent", "AGENT", null, s.id(), s.name(),
                    agentWas, agentNow, AFTER + message, now));
        }
        for (CheckDef c : checks.findByServer(s.id())) {
            if (!c.enabled()) {
                continue;
            }
            Status was = m.snapshot().checks().getOrDefault(c.id(), Status.UNKNOWN);
            CheckState state = states.find(c.id()).orElse(null);
            Status is = state == null ? Status.UNKNOWN : state.status();
            // A check new since the window opened alerts like a new check: only when it is not UP.
            if (is == was || (was == Status.UNKNOWN && (is == Status.UP || is == Status.UNKNOWN))) {
                continue;
            }
            String message = state == null || state.lastMessage() == null ? "now " + is : state.lastMessage();
            out.add(new AlertEvent(AlertEvent.Kind.CHECK, c.id(), c.name(), c.type().name(), c.target(), s.id(),
                    s.name(), was, is, AFTER + message, now));
        }
        return out;
    }
}
