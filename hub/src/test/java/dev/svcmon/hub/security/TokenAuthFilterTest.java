package dev.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import dev.svcmon.hub.model.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
