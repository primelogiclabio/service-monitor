package io.primelogiclab.svcmon.hub.api;

import java.util.List;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.AuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit, newest first, for administrators (TokenAuthFilter lets nobody else in). {@code next} is the id to pass as
 * {@code before} for the page after this one, or absent when this page is the last.
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    public record Page(List<AuditEvent> events, Long next) {
    }

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    public Page list(@RequestParam(required = false) String category, @RequestParam(required = false) Long before,
                     @RequestParam(defaultValue = "100") int limit) {
        if (category != null && !category.equals(AuditService.SIGN_IN) && !category.equals(AuditService.CHANGE)) {
            throw ApiException.badRequest("invalid_category", "category is sign_in or change, or left out for both");
        }
        if (limit < 1 || limit > AuditService.MAX_LIMIT) {
            throw ApiException.badRequest("invalid_limit", "limit is 1 to " + AuditService.MAX_LIMIT);
        }
        List<AuditEvent> events = audit.list(category, before, limit);
        Long next = events.size() == limit ? events.get(events.size() - 1).id() : null;
        return new Page(events, next);
    }
}
