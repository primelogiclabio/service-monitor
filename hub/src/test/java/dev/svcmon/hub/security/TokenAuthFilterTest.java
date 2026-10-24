package dev.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;

import dev.svcmon.hub.model.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TokenAuthFilterTest {

    private static MockHttpServletRequest request(String method, String origin, String requestMethod) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/v1/status");
        if (origin != null) {
            r.addHeader("Origin", origin);
        }
        if (requestMethod != null) {
            r.addHeader("Access-Control-Request-Method", requestMethod);
        }
        return r;
    }

    @Test
    void onlyARealPreflightSkipsTheTokenCheck() {
        assertTrue(TokenAuthFilter.isPreflight(request("OPTIONS", "https://ui.example", "GET")));

        assertFalse(TokenAuthFilter.isPreflight(request("OPTIONS", "https://ui.example", null)), "no requested method");
        assertFalse(TokenAuthFilter.isPreflight(request("OPTIONS", null, "GET")), "no origin");
        assertFalse(TokenAuthFilter.isPreflight(request("GET", "https://ui.example", "GET")), "not OPTIONS");
        assertFalse(TokenAuthFilter.isPreflight(request("POST", "https://ui.example", "POST")), "not OPTIONS");
        assertFalse(TokenAuthFilter.isPreflight(request("DELETE", "https://ui.example", "DELETE")), "not OPTIONS");
    }

    @Test
    void theLiveSocketChecksItsTokenInItsFirstFrameInsteadOfAHeader() throws Exception {
        var filter = new TokenAuthFilter(mock(TokenService.class));

        var liveChain = new MockFilterChain();
        var live = new MockHttpServletRequest("GET", "/api/v1/live");
        var liveResp = new MockHttpServletResponse();
        filter.doFilter(live, liveResp, liveChain);
        assertSame(live, liveChain.getRequest(), "the upgrade request reaches the WebSocket handler");
        assertEquals(200, liveResp.getStatus());

        var statusChain = new MockFilterChain();
        var statusResp = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/status"), statusResp, statusChain);
        assertNull(statusChain.getRequest(), "everything else under /api/ still needs a token");
        assertEquals(401, statusResp.getStatus());
    }

    @Test
    void scopesAreUnchanged() {
        assertEquals(EnumSet.of(Scope.READ, Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/status"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("POST", "/api/v1/checks"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/tokens"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/alert-channels"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/alert-channels/abc"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("POST", "/api/v1/alert-channels/abc/test"));
        assertEquals(EnumSet.of(Scope.AGENT), TokenAuthFilter.required("POST", "/api/v1/agent/report"));
        assertEquals(EnumSet.of(Scope.ENROLL), TokenAuthFilter.required("POST", "/api/v1/agent/register"));
    }
}
