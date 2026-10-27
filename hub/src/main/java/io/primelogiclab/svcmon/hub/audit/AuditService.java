package io.primelogiclab.svcmon.hub.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.AuditEvent;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import io.primelogiclab.svcmon.hub.security.SessionService;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import io.primelogiclab.svcmon.hub.store.AuditRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The audit: who signed in (or tried to), and who changed what, kept for svcmon.audit.sign-in-days and
 * change-days. It records names, addresses, what was called and how it ended, never a request body, a password or a
 * token, so nothing secret ends up in it. Text that comes from outside (a user name, a path) is trimmed and has its
 * control characters removed. A failure to write an event is logged and does not fail the request.
 * <p>
 * Sign-in events are written where they happen (UserService, SessionController, TokenAuthFilter). Change events are
 * written by {@link #requestDone} once a person's POST, PUT, PATCH or DELETE under /api/ has been answered: the route
 * pattern (POST /api/v1/checks/{id}), the id in the path, the outcome and who it was. A controller may add a target
 * and a detail in the request attributes {@link #TARGET_ATTR} and {@link #DETAIL_ATTR}. Agents only appear when they
 * register.
 */
@Service
public class AuditService {

    public static final String SIGN_IN = "sign_in";
    public static final String CHANGE = "change";

    /** Request attributes a controller may set to say what a change was about, and the hub's error code. */
    public static final String TARGET_ATTR = "svcmon.audit.target";
    public static final String DETAIL_ATTR = "svcmon.audit.detail";
    public static final String ERROR_ATTR = "svcmon.audit.error";

    /** Repeated refusals (too many attempts, refused from outside) are recorded once per address in this time. */
    static final long ONCE_MS = TimeUnit.MINUTES.toMillis(15);
    static final int MAX_REMEMBERED = 10_000;
    static final int MAX_TEXT = 200;
    public static final int MAX_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Writes that are recorded as sign-in events instead (signing in and out, passwords, ending a sign-in), so the
     * audit does not show them twice; the live ticket is neither.
     */
    private static final Set<String> SIGN_IN_MECHANICS = Set.of(
            "POST /api/v1/session", "DELETE /api/v1/session", "POST /api/v1/session/live-ticket",
            "PUT /api/v1/me/password", "POST /api/v1/users/{id}/password-reset", "DELETE /api/v1/sessions/{id}");

    /** Who did something: a name and what kind of credential it was (user, jwt, token, ...). */
    public record Actor(String name, String kind) {

        public static final Actor UNKNOWN = new Actor("unknown", "unknown");

        public static Actor user(String name) {
            return new Actor(name, "user");
        }
    }

    private final AuditRepo repo;
    private final NetworkPolicy network;
    private final HubProperties.Audit cfg;
    private final LongSupplier clock;
    /** Keys recorded recently, for {@link #once}; the oldest are forgotten first. */
    private final Map<String, Long> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    @Autowired
    public AuditService(AuditRepo repo, NetworkPolicy network, HubProperties props) {
        this(repo, network, props.audit(), System::currentTimeMillis);
    }

    AuditService(AuditRepo repo, NetworkPolicy network, HubProperties.Audit audit, LongSupplier clock) {
        this.repo = repo;
        this.network = network;
        this.cfg = audit == null ? HubProperties.Audit.DEFAULT : audit;
        this.clock = clock;
        check("svcmon.audit.sign-in-days", cfg.signInDays());
        check("svcmon.audit.change-days", cfg.changeDays());
    }

    private static void check(String setting, int days) {
        if (days < 1 || days > 3650) {
            throw new IllegalStateException(setting + " must be between 1 and 3650");
        }
    }

    /** A sign-in event: {@code kind} sign_in, sign_out, password_changed, ...; {@code outcome} ok or a refusal's code. */
    public void signIn(String kind, String outcome, Actor actor, String address, String target, String detail) {
        record(SIGN_IN, kind, outcome, actor, address, null, target, detail);
    }

    public void change(String kind, String outcome, Actor actor, String address, String action, String target,
                       String detail) {
        record(CHANGE, kind, outcome, actor, address, action, target, detail);
    }

    /**
     * True the first time {@code key} is seen in {@link #ONCE_MS}, so a refusal that repeats (a script trying again
     * and again) is recorded once per address and window rather than flooding the audit.
     */
    public boolean once(String key) {
        long now = clock.getAsLong();
        synchronized (recent) {
            Long last = recent.get(key);
            if (last != null && now - last < ONCE_MS) {
                return false;
            }
            recent.put(key, now);
            return true;
        }
    }

    /** A request refused for where it comes from (see NetworkPolicy); once per address, code and window. */
    public void refused(String code, String address, String action) {
        if (once("refused:" + code + ":" + address)) {
            record(SIGN_IN, "refused", code, Actor.UNKNOWN, address, action, null, null);
        }
    }

    /** Records the first use of each JWT (by its hash) as a sign-in; a JWT is sent with every call. */
    public void jwtUsed(String rawToken, String user, String outcome, String address) {
        if (once("jwt:" + outcome + ":" + sha256(rawToken))) {
            signIn("jwt_sign_in", outcome, new Actor(user == null ? "unknown" : user, "jwt"), address, null, null);
        }
    }

    /**
     * Records a person's change once it has been answered with {@code status}. Reads, the /agent/ calls and signing in
     * and out are not changes.
     */
    public void requestDone(HttpServletRequest req, int status, Actor actor) {
        String method = req.getMethod();
        String path = req.getRequestURI();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || !path.startsWith("/api/")
                || path.startsWith("/api/v1/agent/")) {
            return;
        }
        String route = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String p ? p : path;
        String action = method + " " + route;
        if (SIGN_IN_MECHANICS.contains(action)) {
            return;
        }
        String target = req.getAttribute(TARGET_ATTR) instanceof String t ? t : pathId(req);
        String detail = req.getAttribute(DETAIL_ATTR) instanceof String d ? d : null;
        String outcome = status < 400 ? "ok"
                : req.getAttribute(ERROR_ATTR) instanceof String code ? code : String.valueOf(status);
        change("api_call", outcome, actor, req.getRemoteAddr(), action, target, detail);
    }

    private static String pathId(HttpServletRequest req) {
        if (req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars
                && !vars.isEmpty()) {
            Object id = vars.containsKey("id") ? vars.get("id") : vars.values().iterator().next();
            return id == null ? null : id.toString();
        }
        return null;
    }

    /** Who made this request, from what TokenAuthFilter found; unknown before it has run. */
    public static Actor actorOf(HttpServletRequest req) {
        if (req.getAttribute(TokenAuthFilter.USER_ATTR) instanceof SessionService.Signed who) {
            return Actor.user(who.user().username());
        }
        if (req.getAttribute(TokenAuthFilter.JWT_USER_ATTR) instanceof String user) {
            return new Actor(user, "jwt");
        }
        if (req.getAttribute(TokenAuthFilter.ATTR) instanceof TokenRecord token) {
            return new Actor(token.name(), "token");
        }
        return Actor.UNKNOWN;
    }

    /** Newest first. {@code category} null means both; {@code beforeId} continues an earlier page. */
    public List<AuditEvent> list(String category, Long beforeId, int limit) {
        return repo.find(category, beforeId, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    /** Deletes events past their category's retention; returns how many. */
    public int purge() {
        long now = clock.getAsLong();
        int removed = repo.deleteOlderThan(SIGN_IN, now - TimeUnit.DAYS.toMillis(cfg.signInDays()))
                + repo.deleteOlderThan(CHANGE, now - TimeUnit.DAYS.toMillis(cfg.changeDays()));
        if (removed > 0) {
            log.info("Retention: removed {} audit event(s) (sign-ins after {} days, changes after {})", removed,
                    cfg.signInDays(), cfg.changeDays());
        }
        return removed;
    }

    private void record(String category, String kind, String outcome, Actor actor, String address, String action,
                        String target, String detail) {
        Actor who = actor == null ? Actor.UNKNOWN : actor;
        String where = address == null ? null : network.isInside(address) ? "inside" : "outside";
        try {
            repo.insert(event(clock.getAsLong(), category, kind, outcome, who.name(), who.kind(), address, where,
                    action, target, detail));
        } catch (RuntimeException e) {
            // The audit must not take the hub down with it; the log still says what happened.
            log.warn("Could not write an audit event ({} {} {}): {}", category, kind, outcome, e.getMessage());
        }
    }

    /** An event with every text field cleaned; also used by the users reset command, which runs without the hub. */
    public static AuditEvent event(long at, String category, String kind, String outcome, String actor,
                                   String actorKind, String address, String network, String action, String target,
                                   String detail) {
        return new AuditEvent(0, at, category, clean(kind), clean(outcome), clean(actor), clean(actorKind),
                clean(address), network, clean(action), clean(target), clean(detail));
    }

    /** One printable line of at most {@value #MAX_TEXT} characters, or null. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String one = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        if (one.isEmpty()) {
            return null;
        }
        return one.length() > MAX_TEXT ? one.substring(0, MAX_TEXT) + "..." : one;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
