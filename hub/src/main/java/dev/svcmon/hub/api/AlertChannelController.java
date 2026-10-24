package dev.svcmon.hub.api;

import java.util.List;

import dev.svcmon.hub.alerts.AlertChannelService;
import dev.svcmon.hub.alerts.AlertChannelService.ChannelRequest;
import dev.svcmon.hub.alerts.AlertChannelService.ChannelView;
import dev.svcmon.hub.alerts.AlertChannelService.TestResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where status changes are sent. ADMIN only (enforced in TokenAuthFilter): the settings include webhook URLs and an
 * SMTP password, which responses always show masked.
 */
@RestController
@RequestMapping("/api/v1/alert-channels")
public class AlertChannelController {

    private final AlertChannelService channels;

    public AlertChannelController(AlertChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    public List<ChannelView> list() {
        return channels.list();
    }

    @GetMapping("/{id}")
    public ChannelView get(@PathVariable String id) {
        return channels.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelView create(@RequestBody ChannelRequest request) {
        return channels.create(request);
    }

    /**
     * Replaces a channel's settings. A secret that is omitted or sent back masked keeps its stored value;
     * the type cannot change.
     */
    @PutMapping("/{id}")
    public ChannelView replace(@PathVariable String id, @RequestBody ChannelRequest request) {
        return channels.replace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        channels.delete(id);
    }

    /** Sends a test alert now, one attempt. Always answers 200; "ok" says whether it was delivered. */
    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable String id) {
        return channels.test(id);
    }
}
