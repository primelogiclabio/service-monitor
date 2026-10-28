package io.primelogiclab.svcmon.hub.live;

import java.util.Map;

import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Notes where a live connection comes from, for {@link LiveHandler}, which decides by it which first frames it
 * accepts (see NetworkPolicy). The upgrade request itself signs nobody in: that is the first frame's job.
 */
@Component
public class LiveHandshake implements HandshakeInterceptor {

    static final String INSIDE = "svcmon.live.inside";
    static final String SECURE = "svcmon.live.secure";

    private final NetworkPolicy network;

    public LiveHandshake(NetworkPolicy network) {
        this.network = network;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        // The filter has already refused an upgrade from where nobody may use the hub. Anything else counts as outside.
        if (request instanceof ServletServerHttpRequest servlet) {
            HttpServletRequest req = servlet.getServletRequest();
            attributes.put(INSIDE, network.isInside(req));
            attributes.put(SECURE, req.isSecure());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
