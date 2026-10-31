package io.primelogiclab.svcmon.hub.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.store.SessionRepo;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Sign-ins of people, kept in a cookie the page's scripts cannot read (HttpOnly), so a script injected into the page
 * cannot carry it off. The cookie holds 256 random bits; the hub stores only their SHA-256.
 * <p>
 * Over HTTPS (as the load balancer reports it in X-Forwarded-Proto) the cookie is {@value #SECURE_COOKIE}: Secure,
 * and by the __Host- rule bound to exactly this host, so a sibling subdomain cannot plant one. Over plain HTTP, on
 * the internal network and in development, it is {@value #PLAIN_COOKIE}. Only the name that fits the request is read.
 * SameSite=Strict keeps other sites' pages from sending it; TokenAuthFilter also wants a header they cannot add.
 * <p>
 * A session ends after svcmon.sessions.max-hours, or when it goes unused for svcmon.sessions.idle-minutes. Requests
 * the dashboard makes by itself (marked with {@value #BACKGROUND_HEADER}) do not count as use, so a page left open is
 * signed out like any other.
 */
@Service
public class SessionService {

    public static final String SECURE_COOKIE = "__Host-svcmon";
    public static final String PLAIN_COOKIE = "svcmon";
    public static final String BACKGROUND_HEADER = "X-Svcmon-Background";

    /** A sign-in that was checked: the session and its user, as they are now. */
    public record Signed(SessionRecord session, UserRecord user) {
    }

    /** A new or renewed session and the cookie value, which exists only in this object and the browser. */
    public record Issued(SessionRecord session, String raw) {
    }

    public static final long LIVE_TICKET_SECONDS = 30;
    static final long TOUCH_EVERY_MS = 60_000;
    static final long LIVE_TICKET_MS = TimeUnit.SECONDS.toMillis(LIVE_TICKET_SECONDS);
    private static final int MAX_LIVE_TICKETS = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Ticket(String sessionId, long expiresAt) {
    }

    private final SessionRepo sessions;
    private final UserRepo users;
    private final LongSupplier clock;
    private final long idleMs;
    private final long maxMs;
    private final Map<String, Ticket> tickets = new HashMap<>();

    @Autowired
    public SessionService(SessionRepo sessions, UserRepo users, HubProperties props) {
        this(sessions, users, props, System::currentTimeMillis);
    }

    SessionService(SessionRepo sessions, UserRepo users, HubProperties props, LongSupplier clock) {
        HubProperties.Sessions cfg = props.sessions() == null ? HubProperties.Sessions.DEFAULT : props.sessions();
        if (cfg.idleMinutes() < 5 || cfg.idleMinutes() > 1440) {
            throw new IllegalStateException("svcmon.sessions.idle-minutes must be between 5 and 1440");
        }
        if (cfg.maxHours() < 1 || cfg.maxHours() > 168) {
            throw new IllegalStateException("svcmon.sessions.max-hours must be between 1 and 168");
        }
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
        this.idleMs = TimeUnit.MINUTES.toMillis(cfg.idleMinutes());
        this.maxMs = TimeUnit.HOURS.toMillis(cfg.maxHours());
    }

    /** A sign-in with a password. */
    public Issued create(UserRecord user, String clientAddress, String userAgent) {
        return create(user, clientAddress, userAgent, SessionRecord.PASSWORD);
    }

    /** {@code method} is {@link SessionRecord#PASSWORD} or {@link SessionRecord#PROVIDER}. */
    public Issued create(UserRecord user, String clientAddress, String userAgent, String method) {
        long now = clock.getAsLong();
        String raw = generate();
        SessionRecord s = new SessionRecord(UUID.randomUUID().toString(), user.id(), TokenService.hash(raw), now, now,
                now + maxMs, clientAddress, clip(userAgent), method);
        sessions.insert(s);
        return new Issued(s, raw);
    }

    public Optional<SessionRecord> find(String sessionId) {
        return sessions.findById(sessionId);
    }

    /** The session behind the request's cookie, if it is still going; {@code touch} counts the request as use. */
    public Optional<Signed> authenticate(HttpServletRequest req) {
        String raw = cookie(req);
        if (raw == null || raw.length() < 20 || raw.length() > 200) {
            return Optional.empty();
        }
        Optional<Signed> signed = sessions.findByHash(TokenService.hash(raw)).flatMap(this::current);
        signed.ifPresent(s -> {
            long now = clock.getAsLong();
            if (req.getHeader(BACKGROUND_HEADER) == null && now - s.session().lastSeenAt() >= TOUCH_EVERY_MS) {
                sessions.touch(s.session().id(), now);
            }
        });
        return signed;
    }

    /** True while the session exists, has not ended and its user may still sign in; for long-lived connections. */
    public boolean isActive(String sessionId) {
        return sessions.findById(sessionId).flatMap(this::current).isPresent();
    }

    private Optional<Signed> current(SessionRecord s) {
        long now = clock.getAsLong();
        if (now >= s.expiresAt() || now - s.lastSeenAt() >= idleMs) {
            return Optional.empty();
        }
        return users.findById(s.userId()).filter(u -> !u.disabled()).map(u -> new Signed(s, u));
    }

    /** A new cookie value for the same session, so the old one stops working (after a password change). */
    public Issued renew(SessionRecord s) {
        String raw = generate();
        sessions.rehash(s.id(), TokenService.hash(raw));
        return new Issued(s, raw);
    }

    public boolean end(String sessionId) {
        return sessions.delete(sessionId);
    }

    public int endAll(String userId) {
        return sessions.deleteByUser(userId);
    }

    public int endOthers(String userId, String keepSessionId) {
        return sessions.deleteByUserExcept(userId, keepSessionId);
    }

    public List<SessionRecord> list() {
        return sessions.findAll();
    }

    public List<SessionRecord> listFor(String userId) {
        return sessions.findByUser(userId);
    }

    /** Clears out sessions that have ended, so the table does not grow for ever. */
    public int purge() {
        long now = clock.getAsLong();
        return sessions.deleteEnded(now, now - idleMs);
    }

    /**
     * A one-time ticket for the live WebSocket, which then needs no cookie: the socket can be opened by a page on
     * any site, and a ticket can only be had through a request the cookie's own rules protect.
     */
    public synchronized String liveTicket(SessionRecord s) {
        long now = clock.getAsLong();
        tickets.values().removeIf(t -> t.expiresAt() <= now);
        if (tickets.size() >= MAX_LIVE_TICKETS) {
            throw new IllegalStateException("too many live tickets waiting");
        }
        String ticket = generate();
        tickets.put(ticket, new Ticket(s.id(), now + LIVE_TICKET_MS));
        return ticket;
    }

    /** The session a ticket was made for, once: a ticket works a single time and only while it is fresh. */
    public synchronized Optional<String> redeemLiveTicket(String ticket) {
        Ticket t = ticket == null ? null : tickets.remove(ticket);
        if (t == null || t.expiresAt() <= clock.getAsLong() || !isActive(t.sessionId())) {
            return Optional.empty();
        }
        return Optional.of(t.sessionId());
    }

    public void writeCookie(HttpServletRequest req, HttpServletResponse resp, String raw) {
        resp.addHeader("Set-Cookie", cookieFor(req, raw, -1).toString());
    }

    public void clearCookie(HttpServletRequest req, HttpServletResponse resp) {
        resp.addHeader("Set-Cookie", cookieFor(req, "", 0).toString());
    }

    private static ResponseCookie cookieFor(HttpServletRequest req, String value, long maxAgeSeconds) {
        return cookie(req, SECURE_COOKIE, PLAIN_COOKIE, "Strict", value, maxAgeSeconds);
    }

    /**
     * An HttpOnly cookie for the whole hub: {@code secureName} (a __Host- name) with Secure over HTTPS, {@code
     * plainName} otherwise. {@code maxAgeSeconds} -1 lasts until the browser closes, 0 deletes it.
     */
    public static ResponseCookie cookie(HttpServletRequest req, String secureName, String plainName, String sameSite,
                                        String value, long maxAgeSeconds) {
        boolean secure = req.isSecure();
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(secure ? secureName : plainName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
        if (maxAgeSeconds >= 0) {
            b.maxAge(maxAgeSeconds); // only to delete it: otherwise it lasts until the browser closes, or the hub ends it
        }
        return b.build();
    }

    static String cookie(HttpServletRequest req) {
        return cookie(req, SECURE_COOKIE, PLAIN_COOKIE);
    }

    /** The value of the cookie of that name pair that fits the request (HTTPS or not), or null. */
    public static String cookie(HttpServletRequest req, String secureName, String plainName) {
        String name = req.isSecure() ? secureName : plainName;
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }

    public static boolean hasCookie(HttpServletRequest req) {
        return cookie(req) != null;
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String clip(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String one = userAgent.replaceAll("\\p{Cntrl}+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) : one;
    }
}
