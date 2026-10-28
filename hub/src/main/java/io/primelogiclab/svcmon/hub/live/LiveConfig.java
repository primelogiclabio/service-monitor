package io.primelogiclab.svcmon.hub.live;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Registers the live view's WebSocket. */
@Configuration
@EnableWebSocket
public class LiveConfig implements WebSocketConfigurer {

    private final LiveHandler handler;
    private final LiveHandshake handshake;

    public LiveConfig(LiveHandler handler, LiveHandshake handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Any origin may open the socket: it carries no cookies or other ambient credentials, and sends nothing
        // until the first frame holds a valid token, which a foreign page cannot obtain. Checking the Origin
        // would only break dashboards served from another address behind a load balancer. LiveHandshake only notes
        // where the connection comes from.
        registry.addHandler(handler, LiveHandler.PATH).addInterceptors(handshake).setAllowedOriginPatterns("*");
    }

    /** Frames are tiny (a token or a ping); refuse anything bigger instead of buffering it. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4096);
        container.setMaxBinaryMessageBufferSize(1024);
        return container;
    }
}
