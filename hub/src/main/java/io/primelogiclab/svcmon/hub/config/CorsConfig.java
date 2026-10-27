package io.primelogiclab.svcmon.hub.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Lets a browser app on another origin call the API. Off unless svcmon.cors-allowed-origins lists exact origins.
 * The filter runs before token authentication so that preflight requests (which carry no token) are answered,
 * and so that 401 and 403 responses also carry the CORS headers the page needs to read them.
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(HubProperties props) {
        List<String> origins = validate(props.corsAllowedOrigins());

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false); // tokens travel in a header; the sign-in cookie never works from another origin
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setEnabled(!origins.isEmpty());
        return registration;
    }

    /**
     * Checks that every entry is an exact origin: http or https, a host, an optional port, nothing else.
     * A wildcard is rejected on purpose; list the sites you trust. Throws so a typo stops the hub from
     * starting instead of silently widening or narrowing access.
     */
    public static List<String> validate(List<String> configured) {
        List<String> origins = new ArrayList<>();
        for (String raw : configured == null ? List.<String>of() : configured) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.equals("*")) {
                throw new IllegalArgumentException(
                        "svcmon.cors-allowed-origins: '*' is not allowed; list exact origins such as https://monitor.example.com");
            }
            origins.add(normalize(entry));
        }
        return List.copyOf(origins);
    }

    private static String normalize(String entry) {
        URI uri;
        try {
            uri = new URI(entry);
        } catch (URISyntaxException e) {
            throw invalid(entry);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = (scheme.equals("http") || scheme.equals("https"))
                && uri.getHost() != null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null
                && uri.getFragment() == null
                && uri.getUserInfo() == null;
        if (!ok) {
            throw invalid(entry);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException("svcmon.cors-allowed-origins: '" + entry
                + "' is not an origin; use scheme://host[:port] with no path or trailing slash, e.g. https://monitor.example.com");
    }
}
