package io.primelogiclab.svcmon.hub.alerts;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.AlertChannel;
import io.primelogiclab.svcmon.hub.model.AlertType;
import io.primelogiclab.svcmon.hub.model.Status;
import io.primelogiclab.svcmon.hub.store.AlertChannelRepo;
import org.springframework.stereotype.Service;

/**
 * Creates, validates and updates alert channels. Channel settings contain secrets (webhook URLs, the SMTP
 * password, signing secrets), so they are checked strictly, never returned unmasked, and kept when an update sends
 * the masked value back.
 */
@Service
public class AlertChannelService {

    /** What stands in for a secret in API responses. Sending it back on update means "keep the stored value". */
    public static final String MASK = "********";

    public record ChannelRequest(String name, AlertType type, Boolean enabled, List<Status> statuses, Map<String, Object> config) {
    }

    public record ChannelView(String id, String name, AlertType type, boolean enabled, List<Status> statuses,
                              Map<String, Object> config, Long lastAttemptAt, Long lastSuccessAt, String lastError,
                              long createdAt) {
    }

    public record TestResult(boolean ok, String error) {
    }

    private static final Set<Status> DEFAULT_STATUSES = EnumSet.of(Status.DOWN, Status.WARN, Status.UP);
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");
    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@<>,;()\\[\\]\\\\\":]+@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host", "content-length", "content-type", "transfer-encoding", "connection", "expect", "upgrade");

    private static final Map<AlertType, Set<String>> ALLOWED_KEYS = Map.of(
            AlertType.WEBHOOK, Set.of("url", "headers", "signingSecret"),
            AlertType.SLACK, Set.of("url"),
            AlertType.DISCORD, Set.of("url"),
            AlertType.EMAIL, Set.of("smtpHost", "smtpPort", "security", "username", "password", "from", "to", "format"));

    private final AlertChannelRepo repo;
    private final AlertDispatcher dispatcher;
    private final HubProperties props;

    public AlertChannelService(AlertChannelRepo repo, AlertDispatcher dispatcher, HubProperties props) {
        this.repo = repo;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    public List<ChannelView> list() {
        return repo.findAll().stream().map(AlertChannelService::view).toList();
    }

    public ChannelView get(String id) {
        return view(find(id));
    }

    private AlertChannel find(String id) {
        return repo.find(id).orElseThrow(() -> ApiException.notFound("Alert channel"));
    }

    public ChannelView create(ChannelRequest req) {
        String name = validName(req.name());
        if (req.type() == null) {
            throw ApiException.badRequest("type is required (WEBHOOK, SLACK, DISCORD, EMAIL)");
        }
        if (repo.findByName(name).isPresent()) {
            throw ApiException.conflict("An alert channel named '" + name + "' already exists");
        }
        AlertChannel channel = new AlertChannel(UUID.randomUUID().toString(), name, req.type(),
                normalize(req.type(), req.config()), validStatuses(req.statuses()),
                req.enabled() == null || req.enabled(), System.currentTimeMillis(), null, null, null);
        repo.insert(channel);
        return view(channel);
    }

    public ChannelView replace(String id, ChannelRequest req) {
        AlertChannel existing = find(id);
        if (req.type() != null && req.type() != existing.type()) {
            throw ApiException.badRequest("A channel's type cannot be changed; create a new channel instead");
        }
        String name = validName(req.name() == null ? existing.name() : req.name());
        if (!name.equals(existing.name()) && repo.findByName(name).isPresent()) {
            throw ApiException.conflict("An alert channel named '" + name + "' already exists");
        }
        Map<String, Object> merged = mergeSecrets(existing.type(), req.config(), existing.config());
        AlertChannel updated = new AlertChannel(existing.id(), name, existing.type(), normalize(existing.type(), merged),
                req.statuses() == null ? existing.statuses() : validStatuses(req.statuses()),
                req.enabled() == null ? existing.enabled() : req.enabled(), existing.createdAt(),
                existing.lastAttemptAt(), existing.lastSuccessAt(), existing.lastError());
        repo.update(updated);
        return view(updated);
    }

    public void delete(String id) {
        if (!repo.delete(id)) {
            throw ApiException.notFound("Alert channel");
        }
    }

    /** Sends a test message right now (one attempt) so a person can see whether the channel works. */
    public TestResult test(String id) {
        AlertChannel channel = find(id);
        String error = dispatcher.deliverOnce(channel, AlertEvent.test(System.currentTimeMillis()));
        return new TestResult(error == null, error);
    }

    // ---- validation ----

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 100 || !AlertFormat.oneLine(name).equals(name)) {
            throw ApiException.badRequest("name is required: 1-100 characters, no control characters");
        }
        return name;
    }

    private static Set<Status> validStatuses(List<Status> requested) {
        if (requested == null) {
            return EnumSet.copyOf(DEFAULT_STATUSES);
        }
        if (requested.isEmpty() || requested.contains(null)) {
            throw ApiException.badRequest("statuses must list at least one of DOWN, WARN, UP, UNKNOWN");
        }
        return EnumSet.copyOf(requested);
    }

    Map<String, Object> normalize(AlertType type, Map<String, Object> raw) {
        Map<String, Object> config = raw == null ? Map.of() : raw;
        Set<String> allowed = ALLOWED_KEYS.get(type);
        for (String key : config.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiException.badRequest("Unknown setting '" + key + "' for a " + type + " channel; allowed: "
                        + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case WEBHOOK -> {
                out.put("url", url(config, false));
                putHeaders(config, out);
                String secret = optional(config, "signingSecret", 200);
                if (secret != null) {
                    out.put("signingSecret", secret);
                }
            }
            case SLACK, DISCORD -> out.put("url", url(config, !props.allowInsecureAlertUrls()));
            case EMAIL -> normalizeEmail(config, out);
        }
        return out;
    }

    private static String optional(Map<String, Object> config, String key, int max) {
        Object v = config.get(key);
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        String s = v.toString().trim();
        if (s.length() > max || !AlertFormat.oneLine(s).equals(s)) {
            throw ApiException.badRequest(key + " must be at most " + max + " characters with no control characters");
        }
        return s;
    }

    /** The URL is a secret, so it is never echoed back in an error message. */
    private static String url(Map<String, Object> config, boolean httpsOnly) {
        Object v = config.get("url");
        if (v == null || v.toString().isBlank()) {
            throw ApiException.badRequest("url is required");
        }
        String text = v.toString().trim();
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = scheme.equals("https") || (!httpsOnly && scheme.equals("http"));
        if (uri.getHost() == null || !schemeOk || uri.getUserInfo() != null || text.length() > 2000) {
            throw ApiException.badRequest(httpsOnly
                    ? "url must be an https:// URL with a host and no credentials"
                    : "url must be an http:// or https:// URL with a host and no credentials");
        }
        return text;
    }

    private static void putHeaders(Map<String, Object> config, Map<String, Object> out) {
        Object h = config.get("headers");
        if (h == null) {
            return;
        }
        if (!(h instanceof Map<?, ?> headers)) {
            throw ApiException.badRequest("headers must be an object of header names and values");
        }
        if (headers.size() > 10) {
            throw ApiException.badRequest("At most 10 headers");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : headers.entrySet()) {
            String name = String.valueOf(e.getKey());
            String value = e.getValue() == null ? "" : e.getValue().toString();
            if (!HEADER_NAME.matcher(name).matches() || FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("'" + AlertFormat.truncate(AlertFormat.oneLine(name), 40) + "' is not an allowed header name");
            }
            if (value.isBlank() || value.length() > 1000 || !AlertFormat.oneLine(value).equals(value)) {
                throw ApiException.badRequest("The value of header " + name + " must be 1-1000 characters with no line breaks");
            }
            clean.put(name, value);
        }
        if (!clean.isEmpty()) {
            out.put("headers", clean);
        }
    }

    private static void normalizeEmail(Map<String, Object> config, Map<String, Object> out) {
        String host = optional(config, "smtpHost", 253);
        if (host == null || !HOST.matcher(host).matches()) {
            throw ApiException.badRequest("smtpHost is required and must be a host name or IP address");
        }
        out.put("smtpHost", host);

        int port = 587;
        Object p = config.get("smtpPort");
        if (p != null && !p.toString().isBlank()) {
            try {
                port = p instanceof Number n ? n.intValue() : Integer.parseInt(p.toString().trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("smtpPort must be a number between 1 and 65535");
            }
            if (port < 1 || port > 65535) {
                throw ApiException.badRequest("smtpPort must be a number between 1 and 65535");
            }
        }
        out.put("smtpPort", port);

        String security = optional(config, "security", 20);
        security = security == null ? "STARTTLS" : security.toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "STARTTLS", "SSL").contains(security)) {
            throw ApiException.badRequest("security must be NONE, STARTTLS or SSL");
        }
        out.put("security", security);

        // HTML sends a plain-text and an HTML part; TEXT only the plain text, for pagers and email-to-SMS gateways.
        String format = optional(config, "format", 20);
        format = format == null ? "HTML" : format.toUpperCase(Locale.ROOT);
        if (!Set.of("HTML", "TEXT").contains(format)) {
            throw ApiException.badRequest("format must be HTML or TEXT");
        }
        out.put("format", format);

        String username = optional(config, "username", 200);
        String password = optional(config, "password", 200);
        if (password != null && username == null) {
            throw ApiException.badRequest("password needs a username");
        }
        if (username != null) {
            out.put("username", username);
        }
        if (password != null) {
            out.put("password", password);
        }

        out.put("from", address(config.get("from"), "from"));
        List<String> to = new ArrayList<>();
        Object t = config.get("to");
        List<String> rawTo = t instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                : t == null ? List.of() : Arrays.asList(t.toString().split(","));
        for (String a : rawTo) {
            if (!a.isBlank()) {
                to.add(address(a, "to"));
            }
        }
        if (to.isEmpty() || to.size() > 20) {
            throw ApiException.badRequest("to needs between 1 and 20 email addresses");
        }
        out.put("to", List.copyOf(to));
    }

    private static String address(Object v, String field) {
        String a = v == null ? "" : v.toString().trim();
        if (a.length() > 254 || !ADDRESS.matcher(a).matches()) {
            throw ApiException.badRequest(field + " must be a plain email address such as alerts@example.com");
        }
        return a;
    }

    // ---- secrets ----

    private static boolean isMasked(Object v) {
        return v == null || v.toString().isBlank() || v.toString().contains(MASK);
    }

    /** On update, a secret that is missing or masked keeps its stored value. */
    static Map<String, Object> mergeSecrets(AlertType type, Map<String, Object> incoming, Map<String, Object> existing) {
        Map<String, Object> merged = new LinkedHashMap<>(incoming == null ? Map.of() : incoming);
        for (String key : secretKeys(type)) {
            if (isMasked(merged.get(key))) {
                if (existing.get(key) != null) {
                    merged.put(key, existing.get(key));
                } else {
                    merged.remove(key);
                }
            }
        }
        if (type == AlertType.WEBHOOK && merged.get("headers") instanceof Map<?, ?> headers
                && existing.get("headers") instanceof Map<?, ?> old) {
            Map<String, Object> keep = new LinkedHashMap<>();
            headers.forEach((k, v) -> keep.put(String.valueOf(k), isMasked(v) && old.get(k) != null ? old.get(k) : v));
            merged.put("headers", keep);
        }
        return merged;
    }

    private static List<String> secretKeys(AlertType type) {
        return switch (type) {
            case WEBHOOK -> List.of("url", "signingSecret");
            case SLACK, DISCORD -> List.of("url");
            case EMAIL -> List.of("password");
        };
    }

    static Map<String, Object> mask(AlertType type, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config);
        for (String key : secretKeys(type)) {
            Object v = out.get(key);
            if (v == null || v.toString().isBlank()) {
                continue;
            }
            out.put(key, key.equals("url") ? maskUrl(v.toString()) : MASK);
        }
        if (type == AlertType.WEBHOOK && out.get("headers") instanceof Map<?, ?> headers) {
            Map<String, Object> hidden = new LinkedHashMap<>();
            headers.keySet().forEach(k -> hidden.put(String.valueOf(k), MASK));
            out.put("headers", hidden);
        }
        return out;
    }

    /** Keeps the scheme and host, hides the path where webhook secrets live. */
    static String maskUrl(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "") + "/" + MASK;
        } catch (IllegalArgumentException e) {
            return MASK;
        }
    }

    static ChannelView view(AlertChannel c) {
        return new ChannelView(c.id(), c.name(), c.type(), c.enabled(),
                c.statuses().stream().sorted().toList(), mask(c.type(), c.config()),
                c.lastAttemptAt(), c.lastSuccessAt(), c.lastError(), c.createdAt());
    }
}
