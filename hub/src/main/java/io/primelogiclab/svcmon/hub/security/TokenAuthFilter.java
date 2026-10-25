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
 * Bearer-token check for everything under /api/ except the health probe and the live WebSocket, which cannot
 * send a header from a browser and checks its token in its first frame instead.
 * Reads need READ or ADMIN, writes need ADMIN, /agent/register needs an ENROLL token or a join key and the other
 * /agent/ calls need the agent's own token.
 * With JWT sign-in on, a bearer value shaped like a JWT is checked as an OpenID Connect access token (see
 * {@link JwtAuth}) instead of one of the hub's own tokens, and an administrator's is treated like an ADMIN token
 * on everything except the /agent/ calls.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String ATTR = "svcmon.token";

    /** Set to the user's name when an administrator signed in with a JWT (see {@link JwtAuth}). */
    public static final String JWT_USER_ATTR = "svcmon.jwt-user";

    private static final Logger log = LoggerFactory.getLogger(TokenAuthFilter.class);

    private static final String HEALTH = "/api/v1/health";

    private final TokenService tokens;
    private final JwtAuth jwt;

    public TokenAuthFilter(TokenService tokens, JwtAuth jwt) {
        this.tokens = tokens;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || path.equals(HEALTH) || path.equals(LiveHandler.PATH) || isPreflight(req)
                || isLogin(req)) {
            chain.doFilter(req, resp);
            return;
        }
        String header = req.getHeader("Authorization");
        String raw = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        if (jwt.handles(raw)) {
            viaJwt(req, resp, chain, path, jwt.verify(raw));
            return;
        }
        Optional<TokenRecord> token = tokens.authenticate(raw);
        if (token.isEmpty()) {
            log.warn("Rejected {} {} from {}: missing or invalid token", req.getMethod(), path, req.getRemoteAddr());
            resp.setHeader("WWW-Authenticate", "Bearer");
            reject(resp, 401, "unauthorized", "A valid bearer token is required");
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

    /** The password sign-in is how a token is obtained, so it cannot ask for one; it checks the password itself. */
    static boolean isLogin(HttpServletRequest req) {
        return "POST".equals(req.getMethod()) && req.getRequestURI().equals("/api/v1/login");
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

    static Set<Scope> required(String method, String path) {
        if (path.equals("/api/v1/agent/register")) {
            return EnumSet.of(Scope.ENROLL, Scope.JOIN);
        }
        if (path.startsWith("/api/v1/agent/")) {
            return EnumSet.of(Scope.AGENT);
        }
        if (path.startsWith("/api/v1/tokens") || path.startsWith("/api/v1/enrollment-tokens")
                || path.startsWith("/api/v1/alert-channels") || path.startsWith("/api/v1/join-keys")) {
            return EnumSet.of(Scope.ADMIN);
        }
        boolean read = method.equals("GET") || method.equals("HEAD");
        return read ? EnumSet.of(Scope.READ, Scope.ADMIN) : EnumSet.of(Scope.ADMIN);
    }

    private static void reject(HttpServletResponse resp, int status, String error, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        String body = "{\"error\":\"" + error + "\",\"message\":\"" + message.replace("\"", "'") + "\"}";
        resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
