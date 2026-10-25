package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.Scope;
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
        var filter = new TokenAuthFilter(mock(TokenService.class), new JwtAuth(HubProperties.Jwt.DISABLED, null));

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

    private static TokenAuthFilter jwtFilter() {
        return new TokenAuthFilter(mock(TokenService.class), JwtAuthTest.auth());
    }

    private static MockHttpServletResponse run(TokenAuthFilter filter, MockHttpServletRequest req, MockFilterChain chain)
            throws Exception {
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    private static MockHttpServletRequest bearer(String method, String path, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    @Test
    void anAdministratorsJwtReachesTheApiLikeAnAdminToken() throws Exception {
        var chain = new MockFilterChain();
        var req = bearer("GET", "/api/v1/tokens", JwtAuthTest.PROVIDER.admin());
        var resp = run(jwtFilter(), req, chain);

        assertSame(req, chain.getRequest());
        assertEquals(200, resp.getStatus());
        assertEquals("alice", req.getAttribute(TokenAuthFilter.JWT_USER_ATTR));

        var write = bearer("POST", "/api/v1/checks", JwtAuthTest.PROVIDER.admin());
        var writeChain = new MockFilterChain();
        run(jwtFilter(), write, writeChain);
        assertSame(write, writeChain.getRequest(), "writes need ADMIN and a JWT administrator has it");
    }

    @Test
    void aValidJwtWithoutTheAdminRoleIsForbidden() throws Exception {
        var token = JwtAuthTest.PROVIDER.sign(JwtAuthTest.PROVIDER.claims()
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("staff"))).build());
        var chain = new MockFilterChain();
        var resp = run(jwtFilter(), bearer("GET", "/api/v1/status", token), chain);
        assertNull(chain.getRequest());
        assertEquals(403, resp.getStatus());
        assertFalse(resp.getContentAsString().contains("alice"), "the response does not echo the name");
    }

    @Test
    void aJwtThatIsNotForThisHubIsUnauthorized() throws Exception {
        var token = JwtAuthTest.PROVIDER.sign(JwtAuthTest.PROVIDER.claims().audience("https://golf.example.test/api").build());
        var chain = new MockFilterChain();
        var resp = run(jwtFilter(), bearer("GET", "/api/v1/status", token), chain);
        assertNull(chain.getRequest());
        assertEquals(401, resp.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", resp.getHeader("WWW-Authenticate"));
        assertFalse(resp.getContentAsString().contains(token));
    }

    @Test
    void aJwtSignedByAnotherProviderIsUnauthorized() throws Exception {
        var other = new JwtFixture(JwtAuthTest.ISSUER);
        var chain = new MockFilterChain();
        var resp = run(jwtFilter(), bearer("GET", "/api/v1/status", other.admin()), chain);
        assertNull(chain.getRequest());
        assertEquals(401, resp.getStatus());
    }

    @Test
    void whenTheProvidersKeysCannotBeFetchedTheAnswerIsUnavailable() throws Exception {
        var auth = new JwtAuth(new HubProperties.Jwt("http://127.0.0.1:1/realms/main", JwtFixture.AUDIENCE, null,
                "realm_access.roles", JwtFixture.ADMIN_ROLE), null);
        var chain = new MockFilterChain();
        var resp = run(new TokenAuthFilter(mock(TokenService.class), auth),
                bearer("GET", "/api/v1/status", JwtAuthTest.PROVIDER.admin()), chain);
        assertNull(chain.getRequest());
        assertEquals(503, resp.getStatus());
    }

    @Test
    void aJwtAdministratorIsNeverAnAgent() throws Exception {
        for (String path : new String[] {"/api/v1/agent/config", "/api/v1/agent/report", "/api/v1/agent/register"}) {
            var chain = new MockFilterChain();
            var resp = run(jwtFilter(), bearer("POST", path, JwtAuthTest.PROVIDER.admin()), chain);
            assertNull(chain.getRequest(), path);
            assertEquals(403, resp.getStatus(), path);
        }
    }

    @Test
    void theHubsOwnTokensAreStillCheckedAsTheHubsOwnTokens() throws Exception {
        var tokens = mock(TokenService.class);
        var record = new io.primelogiclab.svcmon.hub.model.TokenRecord("id", "cli", Scope.ADMIN, null, "hash", 0L, null,
                null, null, 0);
        org.mockito.Mockito.when(tokens.authenticate("test-admin-token-0123456789abcdef0123456789abcdef"))
                .thenReturn(java.util.Optional.of(record));
        var filter = new TokenAuthFilter(tokens, JwtAuthTest.auth());

        var chain = new MockFilterChain();
        var req = bearer("GET", "/api/v1/status", "test-admin-token-0123456789abcdef0123456789abcdef");
        assertEquals(200, run(filter, req, chain).getStatus());
        assertSame(req, chain.getRequest());
        assertSame(record, req.getAttribute(TokenAuthFilter.ATTR));
        assertNull(req.getAttribute(TokenAuthFilter.JWT_USER_ATTR));
    }

    @Test
    void whenJwtSignInIsOffAJwtIsJustAnUnknownToken() throws Exception {
        var filter = new TokenAuthFilter(mock(TokenService.class), new JwtAuth(HubProperties.Jwt.DISABLED, null));
        var chain = new MockFilterChain();
        var resp = run(filter, bearer("GET", "/api/v1/status", JwtAuthTest.PROVIDER.admin()), chain);
        assertNull(chain.getRequest());
        assertEquals(401, resp.getStatus());
        assertEquals("Bearer", resp.getHeader("WWW-Authenticate"));
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
        assertEquals(EnumSet.of(Scope.ENROLL, Scope.JOIN), TokenAuthFilter.required("POST", "/api/v1/agent/register"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/join-keys"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("DELETE", "/api/v1/join-keys/abc"));
    }
}
