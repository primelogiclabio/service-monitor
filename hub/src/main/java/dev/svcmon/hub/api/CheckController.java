package dev.svcmon.hub.api;

import java.util.List;

import dev.svcmon.hub.core.CheckRequest;
import dev.svcmon.hub.core.CheckService;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checks")
public class CheckController {

    private final CheckService checks;

    public CheckController(CheckService checks) {
        this.checks = checks;
    }

    @GetMapping
    public List<CheckDef> list() {
        return checks.list();
    }

    @GetMapping("/{id}")
    public CheckDef get(@PathVariable String id) {
        return checks.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckDef create(@RequestBody CheckRequest request) {
        return checks.create(request);
    }

    /** Replaces the whole check; omitted optional fields return to their defaults. */
    @PutMapping("/{id}")
    public CheckDef replace(@PathVariable String id, @RequestBody CheckRequest request) {
        return checks.replace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        checks.delete(id);
    }

    /** Runs a hub-side check now and returns (and stores) the result. */
    @PostMapping("/{id}/run")
    public CheckResult run(@PathVariable String id) {
        return checks.runNow(id);
    }

    /** Newest first. {@code since} is epoch milliseconds. */
    @GetMapping("/{id}/results")
    public List<CheckResult> results(@PathVariable String id,
                                     @RequestParam(required = false) Long since,
                                     @RequestParam(defaultValue = "100") int limit) {
        return checks.history(id, since, limit);
    }
}
