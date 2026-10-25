package io.primelogiclab.svcmon.hub.live;

import java.util.Map;

import io.primelogiclab.svcmon.hub.security.JwtAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
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
 * site can make a browser send. So the browser must say the page is on this origin (see {@link JwtAuth#fromOwnPage}).
 */
@Component
public class LiveHandshake implements HandshakeInterceptor {

    static final String USER = "svcmon.live.user";
    static final String EXPIRES_AT = "svcmon.live.expires-at";

    private static final Logger log = LoggerFactory.getLogger(LiveHandshake.class);

    private final JwtAuth jwt;

    public LiveHandshake(JwtAuth jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
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
            case ADMIN -> JwtAuth.fromOwnPage(headers.getFirst("Sec-Fetch-Site"), headers.getFirst(HttpHeaders.ORIGIN))
                    ? null : HttpStatus.FORBIDDEN;
            case NOT_JWT -> null;
        };
        if (refused != null) {
            log.warn("Rejected live connection from {}: {}", request.getRemoteAddress(),
                    who.kind() == JwtAuth.Kind.ADMIN ? "opened from another site" : who.reason());
            response.setStatusCode(refused);
            return false;
        }
        attributes.put(USER, who.user());
        attributes.put(EXPIRES_AT, who.expiresAtMillis());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
