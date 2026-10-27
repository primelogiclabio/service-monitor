package io.primelogiclab.svcmon.hub.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.AuditEvent;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import io.primelogiclab.svcmon.hub.store.AuditRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/** What the audit writes, what it leaves out, and how long it keeps it. */
class AuditServiceTest {

    private static final long NOW = 1_800_000_000_000L;

    private final AuditRepo repo = mock(AuditRepo.class);
    private final AtomicLong now = new AtomicLong(NOW);
    private final AuditService audit = new AuditService(repo, new NetworkPolicy(HubProperties.Access.DEFAULT),
            HubProperties.Audit.DEFAULT, now::get);

    private AuditEvent lastWritten() {
        ArgumentCaptor<AuditEvent> e = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).insert(e.capture());
        return e.getValue();
    }

    private static MockHttpServletRequest call(String method, String path, String pattern, Map<String, String> vars) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRemoteAddr("10.0.0.7");
        if (pattern != null) {
            r.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        if (vars != null) {
            r.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        }
        return r;
    }

    @Test
    void aChangeIsRecordedByItsRoutePathIdOutcomeAndWho() {
        audit.requestDone(call("PUT", "/api/v1/checks/c1", "/api/v1/checks/{id}", Map.of("id", "c1")), 200,
                AuditService.Actor.user("alice"));
        AuditEvent e = lastWritten();
        assertEquals(AuditService.CHANGE, e.category());
        assertEquals("api_call", e.kind());
        assertEquals("PUT /api/v1/checks/{id}", e.action());
        assertEquals("c1", e.target());
        assertEquals("ok", e.outcome());
        assertEquals("alice", e.actor());
        assertEquals("user", e.actorKind());
        assertEquals("10.0.0.7", e.address());
        assertEquals("inside", e.network());
        assertEquals(NOW, e.at());
    }

    @Test
    void aRefusedChangeIsRecordedByTheHubsErrorCode() {
        MockHttpServletRequest r = call("DELETE", "/api/v1/users/u1", "/api/v1/users/{id}", Map.of("id", "u1"));
        r.setAttribute(AuditService.ERROR_ATTR, "last_admin");
        r.setAttribute(AuditService.TARGET_ATTR, "carol");
        r.setAttribute(AuditService.DETAIL_ATTR, "role READ");
        audit.requestDone(r, 409, AuditService.Actor.user("alice"));
        AuditEvent e = lastWritten();
        assertEquals("last_admin", e.outcome());
        assertEquals("carol", e.target(), "a controller's name beats the id");
        assertEquals("role READ", e.detail());

        audit.requestDone(call("POST", "/api/v1/checks", null, null), 404, AuditService.Actor.UNKNOWN);
        assertEquals("404", lastWritten().outcome());
        assertEquals("POST /api/v1/checks", lastWritten().action(), "without a route the path itself");
    }

    @Test
    void readsAgentCallsAndSigningInAndOutAreNotChanges() {
        AuditService.Actor who = AuditService.Actor.user("alice");
        audit.requestDone(call("GET", "/api/v1/status", "/api/v1/status", null), 200, who);
        audit.requestDone(call("HEAD", "/api/v1/status", "/api/v1/status", null), 200, who);
        audit.requestDone(call("POST", "/api/v1/agent/report", "/api/v1/agent/report", null), 200, who);
        audit.requestDone(call("POST", "/api/v1/session", "/api/v1/session", null), 200, who);
        audit.requestDone(call("DELETE", "/api/v1/session", "/api/v1/session", null), 204, who);
        audit.requestDone(call("POST", "/api/v1/session/live-ticket", "/api/v1/session/live-ticket", null), 200, who);
        // These have sign-in events of their own, so they are not written twice.
        audit.requestDone(call("PUT", "/api/v1/me/password", "/api/v1/me/password", null), 204, who);
        audit.requestDone(call("POST", "/api/v1/users/u1/password-reset", "/api/v1/users/{id}/password-reset", null), 200,
                who);
        audit.requestDone(call("DELETE", "/api/v1/sessions/s1", "/api/v1/sessions/{id}", null), 204, who);
        verify(repo, never()).insert(any());
    }

    @Test
    void textFromOutsideIsOneShortPrintableLine() {
        assertEquals("a b", AuditService.clean(" a\r\n\tb  "));
        assertNull(AuditService.clean("   "));
        assertNull(AuditService.clean(null));
        String longOne = AuditService.clean("x".repeat(500));
        assertEquals(AuditService.MAX_TEXT + 3, longOne.length());
        assertTrue(longOne.endsWith("..."));

        audit.signIn("sign_in", "ok", AuditService.Actor.user("eve\u001b[31m\nadmin"), "203.0.113.7", null, null);
        assertEquals("eve [31m admin", lastWritten().actor(), "no control characters reach the table");
        assertEquals("outside", lastWritten().network());
    }

    @Test
    void aRepeatedRefusalIsRecordedOncePerAddressAndWindow() {
        audit.refused("outside_not_allowed", "203.0.113.7", "GET /api/v1/status");
        audit.refused("outside_not_allowed", "203.0.113.7", "GET /api/v1/checks");
        audit.refused("outside_not_allowed", "203.0.113.8", "GET /api/v1/status");
        verify(repo, times(2)).insert(any());
        AuditEvent e = lastWritten();
        assertEquals("refused", e.kind());
        assertEquals("outside_not_allowed", e.outcome());
        assertEquals("GET /api/v1/status", e.action());
        assertEquals("unknown", e.actor());

        now.addAndGet(AuditService.ONCE_MS);
        audit.refused("outside_not_allowed", "203.0.113.7", "GET /api/v1/status");
        verify(repo, times(3)).insert(any());
    }

    @Test
    void eachJwtIsRecordedTheFirstTimeOnly() {
        audit.jwtUsed("header.payload.signature", "alice", "ok", "10.0.0.7");
        audit.jwtUsed("header.payload.signature", "alice", "ok", "10.0.0.7");
        audit.jwtUsed("header.other.signature", "alice", "ok", "10.0.0.7");
        verify(repo, times(2)).insert(any());
        assertEquals("jwt_sign_in", lastWritten().kind());
        assertEquals("jwt", lastWritten().actorKind());
        assertFalse(lastWritten().toString().contains("signature"), "the token itself is never kept");
    }

    @Test
    void oldEventsGoAfterTheirCategorysDays() {
        audit.purge();
        verify(repo).deleteOlderThan(AuditService.SIGN_IN, NOW - TimeUnit.DAYS.toMillis(30));
        verify(repo).deleteOlderThan(AuditService.CHANGE, NOW - TimeUnit.DAYS.toMillis(365));
    }

    @Test
    void daysOutsideTheRangeStopTheStart() {
        NetworkPolicy network = new NetworkPolicy(HubProperties.Access.DEFAULT);
        var e = assertThrows(IllegalStateException.class,
                () -> new AuditService(repo, network, new HubProperties.Audit(0, 365), now::get));
        assertEquals("svcmon.audit.sign-in-days must be between 1 and 3650", e.getMessage());
        assertThrows(IllegalStateException.class,
                () -> new AuditService(repo, network, new HubProperties.Audit(30, 3651), now::get));
    }

    @Test
    void aBrokenDatabaseDoesNotFailTheRequest() {
        doThrow(new IllegalStateException("disk full")).when(repo).insert(any());
        audit.signIn("sign_in", "ok", AuditService.Actor.user("alice"), "10.0.0.7", null, null);
        verify(repo).insert(any());
        verify(repo, never()).deleteOlderThan(eq(AuditService.SIGN_IN), anyLong());
    }
}
