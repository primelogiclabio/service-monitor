package io.primelogiclab.svcmon.hub.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks svcmon.public-url: the address people reach the dashboard at, used for the links in alert emails. It must
 * be a plain http or https address (a path is fine for a dashboard hosted under one), so a typo fails at start-up
 * and a link built from it can never carry a script, credentials or a query.
 */
public final class PublicUrl {

    private static final int MAX_LENGTH = 200;

    private PublicUrl() {
    }

    /** The address without a trailing slash, or null when unset. Throws IllegalArgumentException for a bad value. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > MAX_LENGTH) {
            throw invalid("it is longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw invalid("it is not a valid address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("it must be an http:// or https:// address with a host, and no user name, query or fragment");
        }
        return text.replaceAll("/+$", "");
    }

    private static IllegalArgumentException invalid(String why) {
        return new IllegalArgumentException("svcmon.public-url: " + why + " (for example https://monitor.example.com)");
    }
}
