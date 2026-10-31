package io.primelogiclab.svcmon.hub.live;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.StatusService;
import io.primelogiclab.svcmon.hub.core.StatusViews.StatusResponse;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import io.primelogiclab.svcmon.hub.security.SessionService;
import io.primelogiclab.svcmon.hub.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's optional live view: one WebSocket at {@value #PATH} that pushes the roll-up (the body of
 * {@code GET /api/v1/status}) whenever it changes, so the page does not have to poll. Polling stays the default
 * and the fallback; the hub works the same without any WebSocket connected.
 *
 * <p>JSON text frames:
 * <ul>
 *   <li>client to hub {@code {"type":"auth","ticket":"..."}} or {@code {"type":"auth","token":"..."}}: must be the
 *       first frame, within {@value #AUTH_TIMEOUT_MS} ms. A signed-in user's page gets the ticket from
 *       POST /api/v1/session/live-ticket.</li>
 *   <li>client to hub {@code {"type":"ping"}}: keeps the link open through proxies; answered with {@code {"type":"pong"}}</li>
 *   <li>hub to client {@code {"type":"status","data":{...}}}: right after authenticating, then after every change</li>
 * </ul>
 *
 * <p>A browser cannot set an Authorization header on a WebSocket, and a token in the URL would end up in access
 * logs, so the token travels in the first frame instead. It needs a READ or ADMIN token, is never logged, and the
 * connection is closed within a second if the token is revoked or expires. A signed-in user sends a one-time ticket
 * instead, so the socket never relies on the session cookie, which the browser would send for any page that opens
 * it; the connection closes when that sign-in ends. Because nothing but that frame authenticates, the browser's
 * Origin does not matter and is not checked. Where the connection comes from
 * limits the first frame as it limits any other request (see {@link NetworkPolicy}): from outside the internal
 * network a token is refused, and a ticket works only over HTTPS while svcmon.access.allow-outside is true.
 *
 * <p>One timer thread does all the work: it drops connections that stayed silent or lost their token, builds the
 * roll-up once per second while someone is watching, and sends each client only what differs from what it last
 * got. That also catches changes no event announces, such as an agent going quiet.
 */
@Component
public class LiveHandler extends TextWebSocketHandler {

    public static final String PATH = "/api/v1/live";

    static final long AUTH_TIMEOUT_MS = 5_000;
    /** The dashboard pings every 25 s; this only cleans up connections that died without closing. */
    static final long IDLE_TIMEOUT_MS = 90_000;
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private static final Logger log = LoggerFactory.getLogger(LiveHandler.class);

    record Frame(String type, String token, String ticket) {
    }

    record StatusFrame(String type, StatusResponse data) {
    }

    private static final class Client {
        final WebSocketSession session;
        final long connectedAt = System.currentTimeMillis();
        volatile long lastFrameAt = connectedAt;
        volatile TokenRecord token;
        /** Set instead when a signed-in user's page sent a live ticket. */
        volatile String sessionId;
        /** Where the connection comes from, as LiveHandshake saw it; unknown counts as outside over plain HTTP. */
        volatile boolean inside;
        volatile boolean secure;
        /** What this client was last sent, without its timestamp, so it is only sent what changed. */
        volatile StatusResponse sent;

        Client(WebSocketSession session) {
            this.session = session;
        }

        boolean authenticated() {
            return token != null || sessionId != null;
        }
    }

    private final TokenService tokens;
    private final SessionService sessions;
    private final NetworkPolicy network;
    private final StatusService status;
    private final HubProperties props;
    private final JsonMapper json;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public LiveHandler(TokenService tokens, SessionService sessions, NetworkPolicy network, StatusService status,
                       HubProperties props, JsonMapper json) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.network = network;
        this.status = status;
        this.props = props;
        this.json = json;
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        if (clients.size() >= props.liveMaxSessions()) {
            log.warn("Refused a live connection: {} already open (svcmon.live-max-sessions)", clients.size());
            raw.close(CloseStatus.SERVICE_OVERLOAD.withReason("too many live connections"));
            return;
        }
        // Sends come from the timer thread and from this handler, and Tomcat allows one at a time.
        Client client = new Client(
                new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        client.inside = Boolean.TRUE.equals(raw.getAttributes().get(LiveHandshake.INSIDE));
        client.secure = Boolean.TRUE.equals(raw.getAttributes().get(LiveHandshake.SECURE));
        clients.put(raw.getId(), client);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Client client = clients.get(raw.getId());
        if (client == null) {
            return;
        }
        client.lastFrameAt = System.currentTimeMillis();

        Frame frame;
        try {
            frame = json.readValue(message.getPayload(), Frame.class);
        } catch (JacksonException e) {
            close(client, CloseStatus.BAD_DATA.withReason("invalid frame"));
            return;
        }
        String type = frame == null || frame.type() == null ? "" : frame.type();

        if (!client.authenticated()) {
            if (type.equals("auth") && frame.ticket() != null) {
                authenticateTicket(client, frame.ticket());
            } else if (type.equals("auth")) {
                authenticate(client, frame.token());
            } else {
                close(client, CloseStatus.POLICY_VIOLATION.withReason("authenticate first"));
            }
            return;
        }
        if (type.equals("ping")) {
            send(client, "{\"type\":\"pong\"}");
        } else {
            close(client, CloseStatus.BAD_DATA.withReason("unknown frame type"));
        }
    }

    private void authenticate(Client client, String raw) {
        if (refused(client, network.forToken(client.inside))) {
            return;
        }
        TokenRecord token = tokens.authenticate(raw).orElse(null);
        if (token == null) {
            log.warn("Rejected live connection from {}: missing or invalid token", client.session.getRemoteAddress());
            close(client, CloseStatus.POLICY_VIOLATION.withReason("unauthorized"));
            return;
        }
        if (token.scope() != Scope.READ && token.scope() != Scope.ADMIN) {
            log.warn("Rejected live connection from {}: token '{}' has scope {}", client.session.getRemoteAddress(),
                    token.name(), token.scope());
            close(client, CloseStatus.POLICY_VIOLATION.withReason("forbidden"));
            return;
        }
        client.token = token;
        push(client, status.status());
    }

    private void authenticateTicket(Client client, String ticket) {
        if (refused(client, network.forUser(client.inside, client.secure))) {
            return;
        }
        String sessionId = sessions.redeemLiveTicket(ticket).orElse(null);
        if (sessionId == null) {
            log.warn("Rejected live connection from {}: unknown, used or expired ticket", client.session.getRemoteAddress());
            close(client, CloseStatus.POLICY_VIOLATION.withReason("unauthorized"));
            return;
        }
        boolean viaProvider = sessions.find(sessionId).map(SessionRecord::viaProvider).orElse(false);
        if (refused(client, network.forSession(client.inside, client.secure, viaProvider))) {
            return;
        }
        client.sessionId = sessionId;
        push(client, status.status());
    }

    /** Closes the connection when where it comes from does not allow this kind of sign-in (see NetworkPolicy). */
    private boolean refused(Client client, NetworkPolicy.Refusal refusal) {
        if (refusal == null) {
            return false;
        }
        log.warn("Rejected live connection from {}: {}", client.session.getRemoteAddress(), refusal.message);
        close(client, CloseStatus.POLICY_VIOLATION.withReason(refusal.code));
        return true;
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable error) {
        log.debug("Live connection error: {}", error.toString());
        Client client = clients.get(raw.getId());
        if (client != null) {
            close(client, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus closeStatus) {
        clients.remove(raw.getId());
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        if (clients.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Client c : clients.values()) {
            if (!c.authenticated()) {
                if (now - c.connectedAt > AUTH_TIMEOUT_MS) {
                    close(c, CloseStatus.POLICY_VIOLATION.withReason("authentication timeout"));
                }
            } else if (now - c.lastFrameAt > IDLE_TIMEOUT_MS) {
                close(c, CloseStatus.GOING_AWAY.withReason("idle"));
            } else if (c.token != null && !tokens.isActive(c.token)) {
                close(c, CloseStatus.POLICY_VIOLATION.withReason("token no longer valid"));
            } else if (c.sessionId != null && !sessions.isActive(c.sessionId)) {
                close(c, CloseStatus.POLICY_VIOLATION.withReason("signed out"));
            }
        }
        if (clients.values().stream().noneMatch(Client::authenticated)) {
            return;
        }
        try {
            StatusResponse current = status.status();
            clients.values().stream().filter(Client::authenticated).forEach(c -> push(c, current));
        } catch (RuntimeException e) {
            // A database hiccup must not end the timer; the next tick tries again.
            log.warn("Could not push the live status: {}", e.getMessage());
        }
    }

    /** Sends the roll-up unless it is what this client already has. */
    private void push(Client client, StatusResponse current) {
        StatusResponse comparable = new StatusResponse(0, current.summary(), current.clusters(), current.hubChecks());
        if (comparable.equals(client.sent)) {
            return;
        }
        client.sent = comparable;
        send(client, frame(current));
    }

    private String frame(StatusResponse current) {
        return json.writeValueAsString(new StatusFrame("status", current));
    }

    private void send(Client client, String text) {
        try {
            client.session.sendMessage(new TextMessage(text));
        } catch (IOException | RuntimeException e) {
            // A slow or dead client: drop it instead of letting it hold up the others.
            log.debug("Dropping a live connection: {}", e.toString());
            close(client, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void close(Client client, CloseStatus reason) {
        clients.remove(client.session.getId());
        try {
            client.session.close(reason);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not close a live connection: {}", e.toString());
        }
    }

    int connections() {
        return clients.size();
    }
}
