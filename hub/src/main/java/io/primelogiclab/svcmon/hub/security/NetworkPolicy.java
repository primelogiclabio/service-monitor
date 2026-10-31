package io.primelogiclab.svcmon.hub.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tells whether a request comes from the internal network, and what a person may do from where they are
 * (svcmon.access). The address is the one Tomcat settled on after reading X-Forwarded-For from trusted proxies
 * (server.forward-headers-strategy), so behind a load balancer it is the person's own.
 * <p>
 * Where a request comes from never grants access; it can only refuse it. A request is outside when its address is
 * not internal, or when it is one of the public proxies: a gateway that forgot X-Forwarded-For shows up as itself,
 * and is then refused rather than let in. A forged X-Forwarded-For sent from inside can only make a request look
 * outside. Agents are never asked: they only call /api/v1/agent/, which this policy does not cover.
 */
@Component
public class NetworkPolicy {

    /** Why a person's request is refused because of where it comes from; the code is the API's error code. */
    public enum Refusal {
        OUTSIDE_NOT_ALLOWED("outside_not_allowed", "Access from outside the internal network is not allowed"),
        INSECURE_TRANSPORT("insecure_transport", "From outside the internal network the hub works over HTTPS only"),
        PASSWORD_INSIDE_ONLY("password_inside_only",
                "From outside the internal network, sign in with the identity provider");

        public final String code;
        public final String message;

        Refusal(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private static final Pattern LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private final HubProperties.Access cfg;
    private final boolean providerSignIn;
    private final List<Cidr> internal;
    private final List<Cidr> publicProxies;

    @Autowired
    public NetworkPolicy(HubProperties props) {
        this(props.access(), props.oidc() != null && props.oidc().enabled());
    }

    public NetworkPolicy(HubProperties.Access access) {
        this(access, false);
    }

    /** {@code providerSignIn}: the hub has an identity provider, so from outside a password is never enough. */
    public NetworkPolicy(HubProperties.Access access, boolean providerSignIn) {
        this.cfg = access == null ? HubProperties.Access.DEFAULT : access;
        this.providerSignIn = providerSignIn;
        this.internal = parse("svcmon.access.internal-networks", cfg.internalNetworks());
        this.publicProxies = parse("svcmon.access.public-proxies", cfg.publicProxies());
    }

    private static List<Cidr> parse(String setting, List<String> values) {
        return (values == null ? List.<String>of() : values).stream().map(v -> {
            try {
                return Cidr.parse(v);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(setting + ": " + e.getMessage());
            }
        }).toList();
    }

    public boolean allowOutside() {
        return cfg.allowOutside();
    }

    public List<String> internalNetworks() {
        return cfg.internalNetworks() == null ? List.of() : cfg.internalNetworks();
    }

    public List<String> publicProxies() {
        return cfg.publicProxies() == null ? List.of() : cfg.publicProxies();
    }

    public boolean isInside(String address) {
        InetAddress a = literal(address);
        return a != null && internal.stream().anyMatch(c -> c.contains(a))
                && publicProxies.stream().noneMatch(c -> c.contains(a));
    }

    public boolean isInside(HttpServletRequest req) {
        return isInside(req.getRemoteAddr());
    }

    /** True when nobody may use the hub from where this request comes, whatever they sign in with. */
    public boolean refusesEveryone(HttpServletRequest req) {
        return !cfg.allowOutside() && !isInside(req);
    }

    /**
     * For a person, however they signed in, or signing in with the identity provider: from outside only when
     * allowed, and only over HTTPS.
     */
    public Refusal forUser(HttpServletRequest req) {
        return forUser(isInside(req), req.isSecure());
    }

    public Refusal forUser(boolean inside, boolean secure) {
        if (inside) {
            return null;
        }
        if (!cfg.allowOutside()) {
            return Refusal.OUTSIDE_NOT_ALLOWED;
        }
        return secure ? null : Refusal.INSECURE_TRANSPORT;
    }

    /**
     * For signing in with a password, or a sign-in made with one: as {@link #forUser}, and on a hub with an identity
     * provider never from outside, where the provider's sign-in is the only way in.
     */
    public Refusal forPassword(HttpServletRequest req) {
        return forPassword(isInside(req), req.isSecure());
    }

    public Refusal forPassword(boolean inside, boolean secure) {
        Refusal refusal = forUser(inside, secure);
        return refusal == null && !inside && providerSignIn ? Refusal.PASSWORD_INSIDE_ONLY : refusal;
    }

    /** For a sign-in made with {@code viaProvider} true (the identity provider) or false (a password). */
    public Refusal forSession(HttpServletRequest req, boolean viaProvider) {
        return viaProvider ? forUser(req) : forPassword(req);
    }

    public Refusal forSession(boolean inside, boolean secure, boolean viaProvider) {
        return viaProvider ? forUser(inside, secure) : forPassword(inside, secure);
    }

    public boolean providerSignIn() {
        return providerSignIn;
    }

    /** For a person's API token (READ or ADMIN, the CLI's): from inside only. */
    public Refusal forToken(boolean inside) {
        return inside ? null : Refusal.OUTSIDE_NOT_ALLOWED;
    }

    /** One line for the start-up summary. */
    public String describe() {
        return (cfg.allowOutside()
                ? "people may sign in from outside over HTTPS" + (providerSignIn ? ", with the identity provider only" : "")
                + " (svcmon.access.allow-outside: true)"
                : "internal network only (svcmon.access.allow-outside: false)")
                + "; internal networks " + (internalNetworks().isEmpty() ? "none" : String.join(", ", internalNetworks()))
                + "; public proxies " + (publicProxies().isEmpty() ? "none" : String.join(", ", publicProxies()));
    }

    /** Parses an IP literal and nothing else: a name would mean a DNS lookup, which is never wanted here. */
    static InetAddress literal(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (!LITERAL.matcher(s).matches()) {
            return null;
        }
        try {
            // For a literal this only parses; an IPv4-mapped IPv6 address comes back as IPv4.
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** An address range such as 10.0.0.0/8; an address without a length is just itself. */
    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            String[] parts = text == null ? new String[] {""} : text.strip().split("/", -1);
            InetAddress a = parts.length <= 2 ? literal(parts[0]) : null;
            if (a == null) {
                throw new IllegalArgumentException("'" + text + "' is not an IP address or range such as 10.0.0.0/8");
            }
            int bits = a.getAddress().length * 8;
            int prefix;
            try {
                prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits;
            } catch (NumberFormatException e) {
                prefix = -1;
            }
            // Not /0: a range that holds every address would make the whole internet inside.
            if (prefix < 1 || prefix > bits) {
                throw new IllegalArgumentException("'" + text + "' needs a range length from 1 to " + bits);
            }
            return new Cidr(a.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] b = address.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xff << (8 - rest) & 0xff;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
