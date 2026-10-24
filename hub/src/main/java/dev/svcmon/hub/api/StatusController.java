package dev.svcmon.hub.api;

import dev.svcmon.hub.core.StatusService;
import dev.svcmon.hub.core.StatusViews.ServerView;
import dev.svcmon.hub.core.StatusViews.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final StatusService status;

    public StatusController(StatusService status) {
        this.status = status;
    }

    /** The roll-up: summary, clusters with their servers and checks, and checks not tied to a server. */
    @GetMapping("/status")
    public StatusResponse status() {
        return status.status();
    }

    @GetMapping("/servers/{id}/status")
    public ServerView server(@PathVariable String id) {
        return status.server(id);
    }
}
