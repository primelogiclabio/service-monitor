package dev.svcmon.hub.api;

import java.util.List;

import dev.svcmon.hub.core.ServerService;
import dev.svcmon.hub.model.ServerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/servers")
public class ServerController {

    public record ServerUpdate(String name, String cluster) {
    }

    private final ServerService servers;

    public ServerController(ServerService servers) {
        this.servers = servers;
    }

    @GetMapping
    public List<ServerInfo> list() {
        return servers.list();
    }

    @GetMapping("/{id}")
    public ServerInfo get(@PathVariable String id) {
        return servers.get(id);
    }

    /** Rename a server or move it to another cluster. Omitted fields are unchanged; "" clears the cluster. */
    @PatchMapping("/{id}")
    public ServerInfo update(@PathVariable String id, @RequestBody ServerUpdate update) {
        return servers.update(id, update.name(), update.cluster());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        servers.delete(id);
    }
}
