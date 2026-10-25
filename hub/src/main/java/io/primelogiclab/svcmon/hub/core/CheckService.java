package io.primelogiclab.svcmon.hub.core;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.checks.PingChecker;
import io.primelogiclab.svcmon.hub.checks.TcpChecker;
import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckSource;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import io.primelogiclab.svcmon.hub.store.ResultRepo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import org.springframework.stereotype.Service;

@Service
public class CheckService {

    /** What Docker and Podman accept as a container name; also covers an ID prefix. */
    private static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,254}");

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
        CheckDef def = build(id, req, existing.createdAt()).withSource(existing.source(), existing.removedFromFile());
        guardDeclared(existing, def);
        checks.update(def);
        scheduler.schedule(def);
        return def;
    }

    /** A check an agent declared is changed in the agent's file, so only its on/off switch can be set here. */
    private static void guardDeclared(CheckDef existing, CheckDef def) {
        if (existing.source() != CheckSource.AGENT) {
            return;
        }
        if (!existing.sameDefinition(def)) {
            throw ApiException.conflict("This check is declared by the agent's checks file; change it there"
                    + " (the next sync would undo an edit here). Only the on/off switch can be set in the hub");
        }
        if (existing.removedFromFile() && def.enabled()) {
            throw ApiException.conflict("This check was removed from the agent's checks file; it stays off until"
                    + " the agent declares it again");
        }
    }

    public void delete(String id) {
        CheckDef existing = checks.find(id).orElse(null);
        if (existing != null && existing.source() == CheckSource.AGENT && !existing.removedFromFile()) {
            throw ApiException.conflict("This check is declared by the agent's checks file; remove it there"
                    + " and it can then be deleted here");
        }
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

    CheckDef build(String id, CheckRequest r, long createdAt) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 200) {
            throw ApiException.badRequest("name is required (max 200 characters)");
        }
        if (r.type() == null) {
            throw ApiException.badRequest("type is required (HTTP, TCP, PING, SERVICE, CONTAINER, CPU, MEMORY, DISK)");
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

    /** A unix socket path, or a Windows named pipe: \\.\pipe\name, //./pipe/name or npipe:////./pipe/name. */
    private static boolean validSocket(String socket) {
        if (socket.length() > 256 || socket.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            return false;
        }
        String pipePrefix = "\\\\.\\pipe\\";
        String path = socket.startsWith("npipe://") ? socket.substring("npipe://".length()) : socket;
        if (socket.startsWith("npipe://") || path.startsWith("\\\\.\\pipe\\") || path.startsWith("//./pipe/")) {
            String pipe = path.replace('/', '\\');
            return pipe.startsWith(pipePrefix) && pipe.length() > pipePrefix.length();
        }
        path = path.startsWith("unix://") ? path.substring("unix://".length()) : path;
        return path.length() > 1 && path.startsWith("/");
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
            case CONTAINER -> {
                if (target == null || !CONTAINER_NAME.matcher(target).matches()) {
                    throw ApiException.badRequest("CONTAINER target must be a container name"
                            + " (letters, digits, underscore, dot and hyphen, starting with a letter or digit)");
                }
                for (String key : config.keySet()) {
                    if (!key.equals("socket")) {
                        throw ApiException.badRequest("CONTAINER checks accept only the config key socket, not " + key);
                    }
                }
                if (config.containsKey("socket")
                        && !(config.get("socket") instanceof String socket && validSocket(socket))) {
                    throw ApiException.badRequest("socket must be the absolute path of the container runtime's"
                            + " unix socket (/var/run/docker.sock) or of its Windows named pipe (\\\\.\\pipe\\docker_engine)");
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
