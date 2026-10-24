package dev.svcmon.hub.core;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.svcmon.hub.config.HubProperties;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.model.Scope;
import dev.svcmon.hub.model.ServerInfo;
import dev.svcmon.hub.model.Status;
import dev.svcmon.hub.model.TokenRecord;
import dev.svcmon.hub.security.TokenService;
import dev.svcmon.hub.store.CheckRepo;
import dev.svcmon.hub.store.ServerRepo;
import dev.svcmon.hub.store.TokenRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Everything the agent protocol needs: register, fetch its check list, report results. */
@Service
public class AgentService {

    private static final long MAX_PAST_MS = 24L * 3_600_000;
    private static final long MAX_FUTURE_MS = 60_000;
    private static final int MAX_BATCH = 1000;

    public record RegisterRequest(String name, String cluster, String os, String agentVersion) {
    }

    public record RegisterResponse(String serverId, String token, int heartbeatSeconds) {
    }

    public record AgentCheck(String id, CheckType type, String target, Map<String, Object> config,
                             int intervalSeconds, int timeoutMs) {
    }

    public record AgentConfig(String serverId, String serverName, String cluster, int heartbeatSeconds,
                              long hubTime, List<AgentCheck> checks) {
    }

    public record ReportedResult(String checkId, Long ts, Status status, Double latencyMs, Double value,
                                 String message) {
    }

    public record ReportRequest(String os, String agentVersion, List<ReportedResult> results) {
    }

    public record ReportResponse(int accepted, int rejected, long hubTime) {
    }

    private final ServerRepo servers;
    private final CheckRepo checks;
    private final TokenRepo tokenRepo;
    private final TokenService tokens;
    private final StateService state;
    private final HubProperties props;

    public AgentService(ServerRepo servers, CheckRepo checks, TokenRepo tokenRepo, TokenService tokens,
                        StateService state, HubProperties props) {
        this.servers = servers;
        this.checks = checks;
        this.tokenRepo = tokenRepo;
        this.tokens = tokens;
        this.state = state;
        this.props = props;
    }

    public RegisterResponse register(TokenRecord enroll, RegisterRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (!ServerService.NAME.matcher(name).matches()) {
            throw ApiException.badRequest("name must be 1-100 characters: letters, digits, space, '.', '_' or '-'");
        }
        if (servers.findByName(name).isPresent()) {
            throw ApiException.conflict("A server named '" + name + "' is already registered");
        }
        if (!tokenRepo.markUsed(enroll.id(), System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This enrollment token has already been used");
        }
        long now = System.currentTimeMillis();
        String cluster = req.cluster() == null || req.cluster().isBlank() ? null : req.cluster().trim();
        ServerInfo server = new ServerInfo(UUID.randomUUID().toString(), name, cluster,
                trim(req.os(), 100), trim(req.agentVersion(), 50), now, now);
        servers.insert(server);
        TokenService.Issued agentToken = tokens.issue("agent:" + name, Scope.AGENT, server.id(), null);
        return new RegisterResponse(server.id(), agentToken.raw(), props.agentHeartbeatSeconds());
    }

    public AgentConfig config(TokenRecord agent) {
        ServerInfo server = servers.find(agent.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        servers.touch(server.id(), System.currentTimeMillis(), null, null);
        List<AgentCheck> list = checks.findAgentEnabled(server.id()).stream()
                .map(c -> new AgentCheck(c.id(), c.type(), c.target(), c.config(), c.intervalSeconds(), c.timeoutMs()))
                .toList();
        return new AgentConfig(server.id(), server.name(), server.cluster(), props.agentHeartbeatSeconds(),
                System.currentTimeMillis(), list);
    }

    public ReportResponse report(TokenRecord agent, ReportRequest req) {
        long now = System.currentTimeMillis();
        servers.find(agent.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        servers.touch(agent.serverId(), now, trim(req.os(), 100), trim(req.agentVersion(), 50));

        List<ReportedResult> incoming = req.results() == null ? List.of() : req.results();
        if (incoming.size() > MAX_BATCH) {
            throw ApiException.badRequest("At most " + MAX_BATCH + " results per report");
        }
        Map<String, CheckDef> own = new HashMap<>();
        for (CheckDef c : checks.findAgentEnabled(agent.serverId())) {
            own.put(c.id(), c);
        }

        int accepted = 0;
        int rejected = 0;
        List<ReportedResult> ordered = incoming.stream()
                .filter(r -> r != null)
                .sorted(Comparator.comparingLong(r -> r.ts() == null ? now : r.ts()))
                .toList();
        for (ReportedResult r : ordered) {
            CheckDef def = r.checkId() == null ? null : own.get(r.checkId());
            if (def == null || def.runFrom() != RunFrom.AGENT || r.status() == null) {
                rejected++;
                continue;
            }
            long ts = r.ts() == null || r.ts() > now + MAX_FUTURE_MS || r.ts() < now - MAX_PAST_MS ? now : r.ts();
            state.record(def, new CheckResult(def.id(), ts, r.status(), r.latencyMs(), r.value(),
                    trim(r.message(), 500)));
            accepted++;
        }
        rejected += incoming.size() - ordered.size();
        return new ReportResponse(accepted, rejected, now);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
