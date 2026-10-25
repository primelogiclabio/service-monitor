package io.primelogiclab.svcmon.hub.core;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.primelogiclab.svcmon.hub.checks.PingChecker;
import io.primelogiclab.svcmon.hub.checks.TcpChecker;
import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import io.primelogiclab.svcmon.hub.store.ResultRepo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import org.springframework.stereotype.Service;

@Service
public class CheckService {

    private final CheckRepo checks;
    private final ServerRepo servers;
    private final ResultRepo results;
    private final CheckScheduler scheduler;
    private final StateService state;

    public CheckService(CheckRepo checks, ServerRepo servers, ResultRepo results,
                        CheckScheduler scheduler, StateService state) {
        this.checks = checks;
        this.servers = servers;
        this.results = results;
        this.scheduler = scheduler;
        this.state = state;
    }

    public List<CheckDef> list() {
        return checks.findAll();
    }

    public CheckDef get(String id) {
        return checks.find(id).orElseThrow(() -> ApiException.notFound("Check"));
    }

    public CheckDef create(CheckRequest req) {
        CheckDef def = build(UUID.randomUUID().toString(), req, System.currentTimeMillis());
        checks.insert(def);
        scheduler.schedule(def);
        return def;
    }

    public CheckDef replace(String id, CheckRequest req) {
        CheckDef existing = get(id);
        CheckDef def = build(id, req, existing.createdAt());
        checks.update(def);
        scheduler.schedule(def);
        return def;
    }

    public void delete(String id) {
        scheduler.unschedule(id);
        if (!checks.delete(id)) {
            throw ApiException.notFound("Check");
        }
        state.forget(id);
    }

    public CheckResult runNow(String id) {
        CheckDef def = get(id);
        if (def.runFrom() != RunFrom.HUB) {
            throw ApiException.badRequest("This check runs on an agent; it cannot be run from the hub");
        }
        return scheduler.runNow(def);
    }

    public List<CheckResult> history(String id, Long since, int limit) {
        get(id);
        int capped = Math.max(1, Math.min(limit, 5000));
        return results.recent(id, since == null ? 0 : since, capped);
    }

    private CheckDef build(String id, CheckRequest r, long createdAt) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 200) {
            throw ApiException.badRequest("name is required (max 200 characters)");
        }
        if (r.type() == null) {
            throw ApiException.badRequest("type is required (HTTP, TCP, PING, SERVICE, CPU, MEMORY, DISK)");
        }
        CheckType type = r.type();
        RunFrom runFrom = r.runFrom() != null ? r.runFrom() : (type.hubCapable() ? RunFrom.HUB : RunFrom.AGENT);
        int interval = r.intervalSeconds() != null ? r.intervalSeconds() : (type.hubCapable() ? 60 : 30);
        int timeout = r.timeoutMs() != null ? r.timeoutMs() : 5000;
        int failures = r.failuresBeforeDown() != null ? r.failuresBeforeDown() : 1;
        boolean enabled = r.enabled() == null || r.enabled();
        Map<String, Object> config = r.config() == null ? Map.of() : r.config();
        String target = r.target() == null ? null : r.target().trim();

        if (interval < 5 || interval > 86_400) {
            throw ApiException.badRequest("intervalSeconds must be between 5 and 86400");
        }
        if (timeout < 100 || timeout > 120_000) {
            throw ApiException.badRequest("timeoutMs must be between 100 and 120000");
        }
        if (failures < 1 || failures > 20) {
            throw ApiException.badRequest("failuresBeforeDown must be between 1 and 20");
        }
        if (config.size() > 50) {
            throw ApiException.badRequest("config has too many entries");
        }
        if (runFrom == RunFrom.HUB && !type.hubCapable()) {
            throw ApiException.badRequest(type + " checks need local access and must run from an agent");
        }
        if (r.serverId() != null && servers.find(r.serverId()).isEmpty()) {
            throw ApiException.badRequest("serverId does not match a registered server");
        }
        if (runFrom == RunFrom.AGENT && r.serverId() == null) {
            throw ApiException.badRequest("serverId is required when a check runs from an agent");
        }
        validateTarget(type, target, config);

        return new CheckDef(id, r.name().trim(), type, target, config, interval, timeout, failures,
                runFrom, r.serverId(), enabled, createdAt);
    }

    private static void validateTarget(CheckType type, String target, Map<String, Object> config) {
        switch (type) {
            case HTTP -> {
                URI uri;
                try {
                    uri = URI.create(target == null ? "" : target);
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("HTTP target must be a valid URL");
                }
                String scheme = uri.getScheme();
                if (uri.getHost() == null || scheme == null
                        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    throw ApiException.badRequest("HTTP target must be an http:// or https:// URL");
                }
            }
            case TCP -> {
                if (TcpChecker.HostPort.parse(target) == null) {
                    throw ApiException.badRequest("TCP target must be host:port");
                }
            }
            case PING -> {
                if (!PingChecker.validHost(target)) {
                    throw ApiException.badRequest("PING target must be a host name or IP address");
                }
            }
            case SERVICE, DISK -> {
                if (target == null || target.isBlank() || target.length() > 512) {
                    throw ApiException.badRequest(type + " target is required"
                            + (type == CheckType.SERVICE ? " (service name)" : " (volume or path)"));
                }
            }
            case CPU, MEMORY -> {
                // no target needed
            }
        }
        if (type == CheckType.CPU || type == CheckType.MEMORY || type == CheckType.DISK) {
            for (String key : List.of("warn", "crit")) {
                Object v = config.get(key);
                if (v != null && !(v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 100)) {
                    throw ApiException.badRequest(key + " must be a number between 0 and 100 (percent used)");
                }
            }
        }
    }
}
