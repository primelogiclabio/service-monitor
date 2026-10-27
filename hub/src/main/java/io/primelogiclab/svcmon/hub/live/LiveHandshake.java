package io.primelogiclab.svcmon.hub.live;

import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.config.CorsConfig;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.security.JwtAuth;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Signs a live connection in from the upgrade request itself, for a reverse proxy that adds
 * {@code Authorization: Bearer <JWT>} to it (a browser cannot set that header on a WebSocket). The result goes to
 * {@link LiveHandler} in the session attributes. A request without such a header is left alone and signs in with
 * a token in its first frame, as before.
 *
 * <p>Unlike the first frame, this credential is added by the proxy from the browser's session cookie, which any
 * site can make a browser send. So the page must be on this site, as the browser itself says (see {@link JwtAuth#fromOwnPage}).
 */
@Component
public class LiveHandshake implements HandshakeInterceptor {

    static final String USER = "svcmon.live.user";
    static final String EXPIRES_AT = "svcmon.live.expires-at";
    static final String INSIDE = "svcmon.live.inside";
    static final String SECURE = "svcmon.live.secure";

    private static final Logger log = LoggerFactory.getLogger(LiveHandshake.class);

    private final JwtAuth jwt;
    private final NetworkPolicy network;
    private final List<String> allowedOrigins;

    public LiveHandshake(JwtAuth jwt, NetworkPolicy network, HubProperties props) {
        this.jwt = jwt;
        this.network = network;
        this.allowedOrigins = CorsConfig.validate(props.corsAllowedOrigins());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        // Where the connection comes from decides which first frames LiveHandler accepts (see NetworkPolicy). The
        // filter has already refused an upgrade from where nobody may use the hub. Anything else counts as outside.
        if (request instanceof ServletServerHttpRequest servlet) {
            HttpServletRequest req = servlet.getServletRequest();
            attributes.put(INSIDE, network.isInside(req));
            attributes.put(SECURE, req.isSecure());
        }
        HttpHeaders headers = request.getHeaders();
        String header = headers.getFirst(HttpHeaders.AUTHORIZATION);
        String raw = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        if (!jwt.handles(raw)) {
            return true;
        }
        JwtAuth.Result who = jwt.verify(raw);
        HttpStatus refused = switch (who.kind()) {
            case INVALID -> HttpStatus.UNAUTHORIZED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ADMIN -> fromOwnPage(request) ? null : HttpStatus.FORBIDDEN;
            case NOT_JWT -> null;
        };
        if (refused != null) {
            log.warn("Rejected live connection from {}: {}", request.getRemoteAddress(),
                    who.kind() == JwtAuth.Kind.ADMIN ? notOnThisSite(request) : who.reason());
            response.setStatusCode(refused);
            return false;
        }
        attributes.put(USER, who.user());
        attributes.put(EXPIRES_AT, who.expiresAtMillis());
        return true;
    }

    /** Says what was compared, because the usual cause is a proxy that does not pass the public address on. */
    private String notOnThisSite(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        return "the page is not on this site (Origin " + clip(headers.getFirst(HttpHeaders.ORIGIN))
                + ", Sec-Fetch-Site " + clip(headers.getFirst("Sec-Fetch-Site"))
                + ", this hub's address as the proxy passed it " + clip(first(headers.getFirst("X-Forwarded-Proto")))
                + "/" + clip(first(headers.getFirst("X-Forwarded-Host")) != null
                        ? first(headers.getFirst("X-Forwarded-Host")) : headers.getFirst(HttpHeaders.HOST))
                + "); the proxy must send X-Forwarded-Host and X-Forwarded-Proto, or list the origin in svcmon.cors-allowed-origins";
    }

    private static String clip(String value) {
        if (value == null) {
            return "none";
        }
        String one = value.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return one.length() > 100 ? one.substring(0, 100) + "..." : one;
    }

    /** The address the browser used comes from the proxy's X-Forwarded-Host and -Proto, or else the request itself. */
    private boolean fromOwnPage(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String scheme = first(headers.getFirst("X-Forwarded-Proto"));
        if (scheme == null) {
            scheme = request.getURI().getScheme();
        }
        String host = first(headers.getFirst("X-Forwarded-Host"));
        if (host == null) {
            host = first(headers.getFirst(HttpHeaders.HOST));
        }
        return JwtAuth.fromOwnPage(headers.getFirst("Sec-Fetch-Site"), headers.getFirst(HttpHeaders.ORIGIN), scheme, host,
                allowedOrigins);
    }

    private static String first(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return null;
        }
        return commaSeparated.split(",")[0].strip();
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
