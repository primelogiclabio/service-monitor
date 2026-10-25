package io.primelogiclab.svcmon.hub.core;

import java.util.List;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.ServerInfo;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import org.springframework.stereotype.Service;

@Service
public class ServerService {

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}$");

    private final ServerRepo servers;
    private final CheckRepo checks;
    private final CheckScheduler scheduler;
    private final StateService state;

    public ServerService(ServerRepo servers, CheckRepo checks, CheckScheduler scheduler, StateService state) {
        this.servers = servers;
        this.checks = checks;
        this.scheduler = scheduler;
        this.state = state;
    }

    public List<ServerInfo> list() {
        return servers.findAll();
    }

    public ServerInfo get(String id) {
        return servers.find(id).orElseThrow(() -> ApiException.notFound("Server"));
    }

    public ServerInfo update(String id, String name, String cluster) {
        ServerInfo existing = get(id);
        String newName = name == null ? existing.name() : name.trim();
        if (!NAME.matcher(newName).matches()) {
            throw ApiException.badRequest("name must be 1-100 characters: letters, digits, space, '.', '_' or '-'");
        }
        if (!newName.equals(existing.name()) && servers.findByName(newName).isPresent()) {
            throw ApiException.conflict("A server named '" + newName + "' already exists");
        }
        String newCluster = cluster == null ? existing.cluster() : cluster.trim();
        if (newCluster != null && newCluster.length() > 100) {
            throw ApiException.badRequest("cluster must be at most 100 characters");
        }
        servers.update(id, newName, newCluster == null || newCluster.isEmpty() ? null : newCluster);
        return get(id);
    }

    public void delete(String id) {
        get(id);
        for (CheckDef c : checks.findByServer(id)) {
            scheduler.unschedule(c.id());
            state.forget(c.id());
        }
        servers.delete(id);
    }
}
