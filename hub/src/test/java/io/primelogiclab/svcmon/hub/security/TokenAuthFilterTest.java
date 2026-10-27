package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TokenAuthFilterTest {

    static final NetworkPolicy INSIDE_ONLY = new NetworkPolicy(HubProperties.Access.DEFAULT);

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
        var filter = new TokenAuthFilter(mock(TokenService.class), new JwtAuth(HubProperties.Jwt.DISABLED, null),
                mock(SessionService.class), INSIDE_ONLY, mock(AuditService.class));

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
        return new TokenAuthFilter(mock(TokenService.class), JwtAuthTest.auth(), mock(SessionService.class), INSIDE_ONLY, mock(AuditService.class));
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
        var resp = run(new TokenAuthFilter(mock(TokenService.class), auth, mock(SessionService.class), INSIDE_ONLY, mock(AuditService.class)),
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
        var filter = new TokenAuthFilter(tokens, JwtAuthTest.auth(), mock(SessionService.class), INSIDE_ONLY, mock(AuditService.class));

        var chain = new MockFilterChain();
        var req = bearer("GET", "/api/v1/status", "test-admin-token-0123456789abcdef0123456789abcdef");
        assertEquals(200, run(filter, req, chain).getStatus());
        assertSame(req, chain.getRequest());
        assertSame(record, req.getAttribute(TokenAuthFilter.ATTR));
        assertNull(req.getAttribute(TokenAuthFilter.JWT_USER_ATTR));
    }

    private static MockHttpServletRequest outside(MockHttpServletRequest r) {
        r.setRemoteAddr("203.0.113.7");
        return r;
    }

    @Test
    void fromOutsideAJwtIsRefusedBeforeItIsCheckedUnlessOutsideIsAllowed() throws Exception {
        var chain = new MockFilterChain();
        var resp = run(jwtFilter(), outside(bearer("GET", "/api/v1/status", JwtAuthTest.PROVIDER.admin())), chain);
        assertNull(chain.getRequest());
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"error\":\"outside_not_allowed\""), resp.getContentAsString());

        var open = new TokenAuthFilter(mock(TokenService.class), JwtAuthTest.auth(), mock(SessionService.class),
                new NetworkPolicy(new HubProperties.Access(true, HubProperties.Access.DEFAULT.internalNetworks(),
                        java.util.List.of())), mock(AuditService.class));
        var allowed = outside(bearer("GET", "/api/v1/status", JwtAuthTest.PROVIDER.admin()));
        var openChain = new MockFilterChain();
        assertEquals(200, run(open, allowed, openChain).getStatus());
        assertSame(allowed, openChain.getRequest(), "a JWT from a gateway works from outside while that is allowed");
    }

    @Test
    void whereARequestComesFromNeverStopsAnAgentButStopsTheLiveViewForEveryoneElse() throws Exception {
        var filter = jwtFilter();
        for (String path : new String[] {"/api/v1/agent/config", "/api/v1/agent/register"}) {
            var resp = run(filter, outside(bearer("POST", path, "some-agent-token-0123456789abcdef")), new MockFilterChain());
            assertEquals(401, resp.getStatus(), path + ": the token is checked as usual, wherever the agent is");
        }
        var live = run(filter, outside(new MockHttpServletRequest("GET", "/api/v1/live")), new MockFilterChain());
        assertEquals(403, live.getStatus());
        assertTrue(live.getContentAsString().contains("outside_not_allowed"));
        var health = new MockFilterChain();
        run(filter, outside(new MockHttpServletRequest("GET", "/api/v1/health")), health);
        assertNotNull(health.getRequest(), "the load balancer's probe still works");
    }

    @Test
    void whenJwtSignInIsOffAJwtIsJustAnUnknownToken() throws Exception {
        var filter = new TokenAuthFilter(mock(TokenService.class), new JwtAuth(HubProperties.Jwt.DISABLED, null),
                mock(SessionService.class), INSIDE_ONLY, mock(AuditService.class));
        var chain = new MockFilterChain();
        var resp = run(filter, bearer("GET", "/api/v1/status", JwtAuthTest.PROVIDER.admin()), chain);
        assertNull(chain.getRequest());
        assertEquals(401, resp.getStatus());
        assertEquals("Bearer", resp.getHeader("WWW-Authenticate"));
    }

    private static MockHttpServletRequest fromPage(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.addHeader(TokenAuthFilter.REQUEST_HEADER, "1");
        r.addHeader("Sec-Fetch-Site", "same-origin");
        return r;
    }

    @Test
    void signingInAndOutNeedNoSignInButMustComeFromTheHubsOwnPage() throws Exception {
        var filter = jwtFilter();
        for (String method : new String[] {"GET", "POST", "DELETE"}) {
            var req = fromPage(method, "/api/v1/session");
            var chain = new MockFilterChain();
            assertEquals(200, run(filter, req, chain).getStatus(), method);
            assertSame(req, chain.getRequest(), method);
        }
        var get = new MockHttpServletRequest("GET", "/api/v1/session");
        var getChain = new MockFilterChain();
        run(filter, get, getChain);
        assertSame(get, getChain.getRequest(), "asking who is signed in changes nothing, so needs no header");

        var bare = new MockFilterChain();
        assertEquals(403, run(filter, new MockHttpServletRequest("POST", "/api/v1/session"), bare).getStatus(),
                "a form on another site cannot sign the browser in");
        assertNull(bare.getRequest());
        var crossSite = fromPage("POST", "/api/v1/session");
        crossSite.removeHeader("Sec-Fetch-Site");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertEquals(403, run(filter, crossSite, new MockFilterChain()).getStatus());

        for (String[] other : new String[][] {{"PUT", "/api/v1/session"}, {"POST", "/api/v1/sessions"},
                {"POST", "/api/v1/session/live-ticket"}, {"PUT", "/api/v1/me/password"}, {"POST", "/api/v1/login"}}) {
            var blocked = new MockFilterChain();
            assertEquals(401, run(filter, fromPage(other[0], other[1]), blocked).getStatus(), other[0] + " " + other[1]);
            assertNull(blocked.getRequest());
        }
    }

    private static final String COOKIE_VALUE = "c".repeat(43);

    private static SessionService.Signed signed(Role role, boolean mustChange) {
        var user = new UserRecord("u1", "carol", role, "hash", mustChange, false, 0L, null, null);
        return new SessionService.Signed(new SessionRecord("s1", "u1", "h", 0L, 0L, Long.MAX_VALUE, null, null), user);
    }

    private static TokenAuthFilter sessionFilter(SessionService.Signed who) {
        SessionService sessions = mock(SessionService.class);
        org.mockito.Mockito.when(sessions.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.ofNullable(who));
        return new TokenAuthFilter(mock(TokenService.class), JwtAuthTest.auth(), sessions, INSIDE_ONLY, mock(AuditService.class));
    }

    private static MockHttpServletRequest withCookie(MockHttpServletRequest r) {
        r.setCookies(new Cookie(SessionService.PLAIN_COOKIE, COOKIE_VALUE));
        return r;
    }

    @Test
    void aSessionCookieSignsInAUserWhoseRoleCountsAsTheScope() throws Exception {
        var who = signed(Role.READ, false);
        var req = withCookie(new MockHttpServletRequest("GET", "/api/v1/status"));
        var chain = new MockFilterChain();
        assertEquals(200, run(sessionFilter(who), req, chain).getStatus());
        assertSame(req, chain.getRequest());
        assertSame(who, req.getAttribute(TokenAuthFilter.USER_ATTR));

        var write = new MockFilterChain();
        assertEquals(403, run(sessionFilter(who), withCookie(fromPage("POST", "/api/v1/checks")), write).getStatus(),
                "a read-only user cannot write");
        assertNull(write.getRequest());
        var own = new MockFilterChain();
        run(sessionFilter(who), withCookie(fromPage("PUT", "/api/v1/me/password")), own);
        assertNotNull(own.getRequest(), "but may change their own password");
    }

    @Test
    void aWriteWithTheCookieNeedsTheHeaderAndNoOtherSite() throws Exception {
        var who = signed(Role.ADMIN, false);
        var noHeader = new MockFilterChain();
        var resp = run(sessionFilter(who), withCookie(new MockHttpServletRequest("POST", "/api/v1/checks")), noHeader);
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("cross_site"), resp.getContentAsString());
        assertNull(noHeader.getRequest());

        var other = withCookie(new MockHttpServletRequest("DELETE", "/api/v1/checks/abc"));
        other.addHeader(TokenAuthFilter.REQUEST_HEADER, "1");
        other.addHeader("Sec-Fetch-Site", "same-site");
        assertEquals(403, run(sessionFilter(who), other, new MockFilterChain()).getStatus(), "a sibling subdomain too");

        var ok = new MockFilterChain();
        run(sessionFilter(who), withCookie(fromPage("POST", "/api/v1/checks")), ok);
        assertNotNull(ok.getRequest());
    }

    @Test
    void aUserWhoMustChangeTheirPasswordCanDoNothingElse() throws Exception {
        var who = signed(Role.ADMIN, true);
        var resp = run(sessionFilter(who), withCookie(new MockHttpServletRequest("GET", "/api/v1/status")),
                new MockFilterChain());
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("password_change_required"));
        for (String[] allowed : new String[][] {{"PUT", "/api/v1/me/password"}, {"GET", "/api/v1/me"}}) {
            var chain = new MockFilterChain();
            run(sessionFilter(who), withCookie(fromPage(allowed[0], allowed[1])), chain);
            assertNotNull(chain.getRequest(), allowed[1]);
        }
        assertEquals(403, run(sessionFilter(who), withCookie(new MockHttpServletRequest("GET", "/api/v1/sessions")),
                new MockFilterChain()).getStatus());
    }

    @Test
    void anEndedSessionIsTold401() throws Exception {
        var chain = new MockFilterChain();
        var resp = run(sessionFilter(null), withCookie(new MockHttpServletRequest("GET", "/api/v1/status")), chain);
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("no_session"));
        assertNull(chain.getRequest());
    }

    @Test
    void aUserIsNeverAnAgentAndABearerTokenWinsOverTheCookie() throws Exception {
        var admin = signed(Role.ADMIN, false);
        for (String path : new String[] {"/api/v1/agent/config", "/api/v1/agent/report", "/api/v1/agent/register"}) {
            var chain = new MockFilterChain();
            assertEquals(403, run(sessionFilter(admin), withCookie(fromPage("POST", path)), chain).getStatus(), path);
            assertNull(chain.getRequest());
        }
        var both = withCookie(bearer("GET", "/api/v1/status", "not-a-known-token-0123456789abcdef"));
        assertEquals(401, run(sessionFilter(admin), both, new MockFilterChain()).getStatus(),
                "the header is what counts, and that token is unknown");
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
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/users"));
        assertEquals(EnumSet.of(Scope.ADMIN), TokenAuthFilter.required("POST", "/api/v1/users/abc/password-reset"));
        assertEquals(EnumSet.of(Scope.READ, Scope.ADMIN), TokenAuthFilter.required("PUT", "/api/v1/me/password"));
        assertEquals(EnumSet.of(Scope.READ, Scope.ADMIN), TokenAuthFilter.required("DELETE", "/api/v1/sessions/abc"));
        assertEquals(EnumSet.of(Scope.READ, Scope.ADMIN), TokenAuthFilter.required("POST", "/api/v1/session/live-ticket"));
        assertEquals(EnumSet.of(Scope.READ, Scope.ADMIN), TokenAuthFilter.required("GET", "/api/v1/usersx"),
                "only the users path itself");
    }
}
