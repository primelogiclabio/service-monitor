package io.primelogiclab.svcmon.hub.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Tells whether a client address is on the internal network. The address is the one Tomcat settled on after reading
 * X-Forwarded-For from trusted proxies (server.forward-headers-strategy), so behind a load balancer it is the
 * person's own. Being inside never grants access by itself; it only lifts a refusal, such as for the initial
 * password, which works from inside only.
 */
@Component
public class NetworkPolicy {

    /** Loopback, the private IPv4 ranges and IPv6 unique local addresses. */
    static final List<String> INTERNAL = List.of(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");

    private static final Pattern LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private final List<Cidr> internal = INTERNAL.stream().map(Cidr::parse).toList();

    public boolean isInside(String address) {
        InetAddress a = literal(address);
        return a != null && internal.stream().anyMatch(c -> c.contains(a));
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
            String[] parts = text.strip().split("/", -1);
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
