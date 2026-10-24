package dev.svcmon.hub.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.svcmon.hub.config.HubProperties;
import dev.svcmon.hub.core.StatusViews.CheckView;
import dev.svcmon.hub.core.StatusViews.ClusterView;
import dev.svcmon.hub.core.StatusViews.Counts;
import dev.svcmon.hub.core.StatusViews.ServerView;
import dev.svcmon.hub.core.StatusViews.StatusResponse;
import dev.svcmon.hub.core.StatusViews.Summary;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckState;
import dev.svcmon.hub.model.ServerInfo;
import dev.svcmon.hub.model.Status;
import dev.svcmon.hub.store.CheckRepo;
import dev.svcmon.hub.store.ServerRepo;
import dev.svcmon.hub.store.StateRepo;
import org.springframework.stereotype.Service;

/** Builds the roll-up: checks -> servers -> clusters -> overall. */
@Service
public class StatusService {

    static final String DEFAULT_CLUSTER = "default";

    private final CheckRepo checks;
    private final ServerRepo servers;
    private final StateRepo states;
    private final HubProperties props;

    public StatusService(CheckRepo checks, ServerRepo servers, StateRepo states, HubProperties props) {
        this.checks = checks;
        this.servers = servers;
        this.states = states;
        this.props = props;
    }

    public StatusResponse status() {
        long now = System.currentTimeMillis();
        long staleMs = props.agentStaleSeconds() * 1000L;

        Map<String, CheckState> stateById = new HashMap<>();
        states.findAll().forEach(s -> stateById.put(s.checkId(), s));

        Map<String, List<CheckDef>> byServer = new HashMap<>();
        List<CheckDef> hubOnly = new ArrayList<>();
        for (CheckDef c : checks.findAll()) {
            if (c.serverId() == null) {
                hubOnly.add(c);
            } else {
                byServer.computeIfAbsent(c.serverId(), k -> new ArrayList<>()).add(c);
            }
        }

        Map<String, List<ServerView>> clusters = new TreeMap<>();
        List<Status> allChecks = new ArrayList<>();
        List<Status> allServers = new ArrayList<>();
        List<Status> overall = new ArrayList<>();

        for (ServerInfo s : servers.findAll()) {
            boolean online = s.lastSeen() != null && now - s.lastSeen() <= staleMs;
            List<CheckView> views = new ArrayList<>();
            List<Status> rollup = new ArrayList<>();
            rollup.add(online ? Status.UP : Status.DOWN);
            for (CheckDef c : byServer.getOrDefault(s.id(), List.of())) {
                CheckView v = view(c, stateById.get(c.id()));
                views.add(v);
                if (c.enabled()) {
                    rollup.add(v.status());
                    allChecks.add(v.status());
                }
            }
            Status serverStatus = Status.worst(rollup);
            allServers.add(serverStatus);
            overall.add(serverStatus);
            String cluster = s.cluster() == null || s.cluster().isBlank() ? DEFAULT_CLUSTER : s.cluster();
            clusters.computeIfAbsent(cluster, k -> new ArrayList<>())
                    .add(new ServerView(s.id(), s.name(), s.cluster(), s.os(), s.agentVersion(), s.lastSeen(),
                            online, serverStatus, views));
        }

        List<ClusterView> clusterViews = new ArrayList<>();
        clusters.forEach((name, list) -> {
            list.sort(Comparator.comparing(ServerView::name, String.CASE_INSENSITIVE_ORDER));
            clusterViews.add(new ClusterView(name, Status.worst(list.stream().map(ServerView::status).toList()), list));
        });

        List<CheckView> hubViews = new ArrayList<>();
        for (CheckDef c : hubOnly) {
            CheckView v = view(c, stateById.get(c.id()));
            hubViews.add(v);
            if (c.enabled()) {
                allChecks.add(v.status());
                overall.add(v.status());
            }
        }

        Summary summary = new Summary(Status.worst(overall), counts(allServers), counts(allChecks));
        return new StatusResponse(now, summary, clusterViews, hubViews);
    }

    public ServerView server(String id) {
        return status().clusters().stream()
                .flatMap(c -> c.servers().stream())
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Server"));
    }

    private static CheckView view(CheckDef c, CheckState s) {
        return new CheckView(c.id(), c.name(), c.type(), c.target(), c.runFrom(), c.enabled(),
                s == null ? Status.UNKNOWN : s.status(),
                s == null ? null : s.lastLatencyMs(),
                s == null ? null : s.lastValue(),
                s == null ? null : s.lastMessage(),
                s == null ? null : s.lastResultAt(),
                s == null ? null : s.lastChange());
    }

    private static Counts counts(List<Status> list) {
        int up = 0;
        int warn = 0;
        int down = 0;
        int unknown = 0;
        for (Status s : list) {
            switch (s) {
                case UP -> up++;
                case WARN -> warn++;
                case DOWN -> down++;
                case UNKNOWN -> unknown++;
            }
        }
        return new Counts(up, warn, down, unknown);
    }
}
