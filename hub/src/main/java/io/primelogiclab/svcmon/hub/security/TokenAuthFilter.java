package io.primelogiclab.svcmon.hub.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.primelogiclab.svcmon.hub.live.LiveHandler;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The check in front of everything under /api/ except the health probe and the live WebSocket, which cannot
 * send a header from a browser and checks a ticket or token in its first frame instead (only the network rule below
 * applies to its upgrade request).
 * Reads need READ or ADMIN, writes need ADMIN, /agent/register needs an ENROLL token or a join key and the other
 * /agent/ calls need the agent's own token.
 * <p>
 * A request with an Authorization header is checked by its bearer token, and any cookie is ignored. With JWT
 * sign-in on, a bearer value shaped like a JWT is checked as an OpenID Connect access token (see {@link JwtAuth})
 * instead of one of the hub's own tokens, and an administrator's is treated like an ADMIN token on everything except
 * the /agent/ calls.
 * <p>
 * Without one, the session cookie of a signed-in user is checked (see {@link SessionService}); the user's role counts
 * as the token scope of the same name. Because a browser sends that cookie by itself, a request that changes
 * something must also carry {@value #REQUEST_HEADER}: 1, which a page on another site cannot add without asking
 * first (a CORS preflight the hub never approves), and must not come from another site by the browser's own word
 * (Sec-Fetch-Site). A user who must change their password can do only that until they have.
 * <p>
 * Where a person's request comes from can refuse it before any of that (see {@link NetworkPolicy}): from outside the
 * internal network an API token never works, a user only when svcmon.access.allow-outside is true and the request
 * came over HTTPS, a JWT only while allow-outside is true. The /agent/ calls are never refused for where they come
 * from: agents connect out to the hub from wherever their servers are.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String ATTR = "svcmon.token";

    /** Set to the user's name when an administrator signed in with a JWT (see {@link JwtAuth}). */
    public static final String JWT_USER_ATTR = "svcmon.jwt-user";

    /** Set to the {@link SessionService.Signed} of a user signed in with the session cookie. */
    public static final String USER_ATTR = "svcmon.user";

    public static final String REQUEST_HEADER = "X-Svcmon-Request";

    private static final Logger log = LoggerFactory.getLogger(TokenAuthFilter.class);

    private static final String HEALTH = "/api/v1/health";
    static final String SESSION = "/api/v1/session";

    private final TokenService tokens;
    private final JwtAuth jwt;
    private final SessionService sessions;
    private final NetworkPolicy network;

    public TokenAuthFilter(TokenService tokens, JwtAuth jwt, SessionService sessions, NetworkPolicy network) {
        this.tokens = tokens;
        this.jwt = jwt;
        this.sessions = sessions;
        this.network = network;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || path.equals(HEALTH) || isPreflight(req)) {
            chain.doFilter(req, resp);
            return;
        }
        if (path.equals(LiveHandler.PATH)) {
            // The live view signs in with its first frame, where LiveHandler applies the rest of the network rules.
            if (network.refusesEveryone(req)) {
                refuse(req, resp, path, NetworkPolicy.Refusal.OUTSIDE_NOT_ALLOWED);
                return;
            }
            chain.doFilter(req, resp);
            return;
        }
        String header = req.getHeader("Authorization");
        if (isSignIn(req)) {
            // Signing in, out and asking who is signed in need no sign-in; they read the cookie themselves. Signing
            // in and out still change something, so another site's page may not make the browser do them.
            if (!isRead(req.getMethod()) && !fromThisSite(req)) {
                reject(resp, 403, "cross_site", "This request must come from the hub's own page");
                return;
            }
            NetworkPolicy.Refusal refusal = req.getMethod().equals("POST") ? network.forUser(req) : null;
            if (refusal != null) {
                refuse(req, resp, path, refusal);
                return;
            }
            chain.doFilter(req, resp);
            return;
        }
        if (header == null && SessionService.hasCookie(req)) {
            NetworkPolicy.Refusal refusal = network.forUser(req);
            if (refusal != null) {
                refuse(req, resp, path, refusal);
                return;
            }
            viaSession(req, resp, chain, path);
            return;
        }
        String raw = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        boolean inside = network.isInside(req);
        if (jwt.handles(raw)) {
            NetworkPolicy.Refusal refusal = network.forJwt(inside);
            if (refusal != null) {
                refuse(req, resp, path, refusal);
                return;
            }
            viaJwt(req, resp, chain, path, jwt.verify(raw));
            return;
        }
        // A person's API token works from inside only; without any credential, say what would help from there.
        if (!inside && !path.startsWith(AGENT) && (raw != null || !network.allowOutside())) {
            refuse(req, resp, path, network.forToken(false));
            return;
        }
        Optional<TokenRecord> token = tokens.authenticate(raw);
        if (token.isEmpty()) {
            log.warn("Rejected {} {} from {}: missing or invalid token", req.getMethod(), path, req.getRemoteAddr());
            resp.setHeader("WWW-Authenticate", "Bearer");
            reject(resp, 401, "unauthorized", "Sign in, or send a valid bearer token");
            return;
        }
        if (!required(req.getMethod(), path).contains(token.get().scope())) {
            log.warn("Rejected {} {} from {}: token '{}' has scope {}", req.getMethod(), path, req.getRemoteAddr(),
                    token.get().name(), token.get().scope());
            reject(resp, 403, "forbidden", "This token's scope does not allow " + req.getMethod() + " " + path);
            return;
        }
        req.setAttribute(ATTR, token.get());
        chain.doFilter(req, resp);
    }

    /** GET, POST and DELETE of /api/v1/session: who is signed in, signing in and signing out. */
    static boolean isSignIn(HttpServletRequest req) {
        String m = req.getMethod();
        return req.getRequestURI().equals(SESSION) && (m.equals("GET") || m.equals("POST") || m.equals("DELETE"));
    }

    /** A signed-in user, by the session cookie. */
    private void viaSession(HttpServletRequest req, HttpServletResponse resp, FilterChain chain, String path)
            throws IOException, ServletException {
        SessionService.Signed who = sessions.authenticate(req).orElse(null);
        if (who == null) {
            sessions.clearCookie(req, resp);
            reject(resp, 401, "no_session", "The sign-in has ended; sign in again");
            return;
        }
        String method = req.getMethod();
        if (!isRead(method) && !fromThisSite(req)) {
            log.warn("Rejected {} {} from {} for user '{}': not sent by the hub's own page", method, path,
                    req.getRemoteAddr(), who.user().username());
            reject(resp, 403, "cross_site", "This request must come from the hub's own page");
            return;
        }
        if (who.user().mustChangePassword() && !path.equals(PASSWORD) && !path.equals(ME)
                && !path.startsWith(SESSION + "/")) {
            reject(resp, 403, "password_change_required", "Choose a new password first");
            return;
        }
        if (!required(method, path).contains(who.user().role().scope())) {
            log.warn("Rejected {} {} from {}: user '{}' has the role {}", method, path, req.getRemoteAddr(),
                    who.user().username(), who.user().role());
            reject(resp, 403, "forbidden", "Your role does not allow " + method + " " + path);
            return;
        }
        req.setAttribute(USER_ATTR, who);
        chain.doFilter(req, resp);
    }

    private static boolean isRead(String method) {
        return method.equals("GET") || method.equals("HEAD");
    }

    /**
     * True for a request the hub's own page made: it carries the header only a script on this site can add, and
     * the browser does not say it came from elsewhere. Browsers without Sec-Fetch-Site rely on the header alone.
     */
    static boolean fromThisSite(HttpServletRequest req) {
        if (!"1".equals(req.getHeader(REQUEST_HEADER))) {
            return false;
        }
        String site = req.getHeader("Sec-Fetch-Site");
        return site == null || site.equals("same-origin") || site.equals("none");
    }

    /** A JWT: only an administrator's is accepted, and only where an ADMIN token would do. */
    private void viaJwt(HttpServletRequest req, HttpServletResponse resp, FilterChain chain, String path,
                        JwtAuth.Result who) throws IOException, ServletException {
        switch (who.kind()) {
            case INVALID -> {
                log.warn("Rejected {} {} from {}: JWT not accepted: {}", req.getMethod(), path, req.getRemoteAddr(),
                        who.reason());
                resp.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                reject(resp, 401, "unauthorized", "The token is invalid, expired or not meant for this hub");
                return;
            }
            case UNAVAILABLE -> {
                log.warn("Could not check a JWT for {} {} from {}: {}", req.getMethod(), path, req.getRemoteAddr(),
                        who.reason());
                reject(resp, 503, "unavailable", "The sign-in provider's keys could not be fetched; try again shortly");
                return;
            }
            case FORBIDDEN -> {
                log.warn("Rejected {} {} from {}: user '{}' {}", req.getMethod(), path, req.getRemoteAddr(), who.user(),
                        who.reason());
                reject(resp, 403, "forbidden", "Only administrators may use the hub");
                return;
            }
            default -> {
                if (!required(req.getMethod(), path).contains(Scope.ADMIN)) {
                    log.warn("Rejected {} {} from {}: user '{}' is not an agent", req.getMethod(), path,
                            req.getRemoteAddr(), who.user());
                    reject(resp, 403, "forbidden", "A signed-in user may not call " + req.getMethod() + " " + path);
                    return;
                }
                req.setAttribute(JWT_USER_ATTR, who.user());
                chain.doFilter(req, resp);
            }
        }
    }

    /**
     * A browser's CORS preflight: OPTIONS with Origin and Access-Control-Request-Method, sent without a token.
     * It does nothing and returns no data; whether the origin is allowed is decided by CORS handling, which
     * answers allowed origins before this filter and refuses everything else.
     */
    static boolean isPreflight(HttpServletRequest req) {
        return "OPTIONS".equals(req.getMethod())
                && req.getHeader("Origin") != null
                && req.getHeader("Access-Control-Request-Method") != null;
    }

    static final String AGENT = "/api/v1/agent/";
    static final String ME = "/api/v1/me";
    static final String PASSWORD = "/api/v1/me/password";

    static Set<Scope> required(String method, String path) {
        if (path.equals("/api/v1/agent/register")) {
            return EnumSet.of(Scope.ENROLL, Scope.JOIN);
        }
        if (path.startsWith(AGENT)) {
            return EnumSet.of(Scope.AGENT);
        }
        if (path.startsWith("/api/v1/tokens") || path.startsWith("/api/v1/enrollment-tokens")
                || path.startsWith("/api/v1/alert-channels") || path.startsWith("/api/v1/join-keys")
                || path.equals("/api/v1/users") || path.startsWith("/api/v1/users/")) {
            return EnumSet.of(Scope.ADMIN);
        }
        // Everyone's own: their password, their sessions (an administrator sees all), a ticket for the live view.
        if (path.equals(PASSWORD) || path.equals("/api/v1/sessions") || path.startsWith("/api/v1/sessions/")
                || path.startsWith(SESSION + "/")) {
            return EnumSet.of(Scope.READ, Scope.ADMIN);
        }
        boolean read = method.equals("GET") || method.equals("HEAD");
        return read ? EnumSet.of(Scope.READ, Scope.ADMIN) : EnumSet.of(Scope.ADMIN);
    }

    private static void refuse(HttpServletRequest req, HttpServletResponse resp, String path,
                               NetworkPolicy.Refusal refusal) throws IOException {
        log.warn("Refused {} {} from {}: {}", req.getMethod(), path, req.getRemoteAddr(),
                refusal == NetworkPolicy.Refusal.INSECURE_TRANSPORT ? "outside the internal network over plain HTTP"
                        : "outside the internal network");
        reject(resp, 403, refusal.code, refusal.message);
    }

    private static void reject(HttpServletResponse resp, int status, String error, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        String body = "{\"error\":\"" + error + "\",\"message\":\"" + message.replace("\"", "'") + "\"}";
        resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
