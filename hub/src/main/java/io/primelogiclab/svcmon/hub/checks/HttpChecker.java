package io.primelogiclab.svcmon.hub.checks;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.Status;
import org.springframework.stereotype.Component;

/**
 * HTTP(S) check. Config keys: expectStatus (default "200-299", e.g. "200,301-302"),
 * keyword (body must contain it), followRedirects (default true), ignoreTls (default false),
 * certWarnDays (default 14), headers (map).
 */
@Component
public class HttpChecker implements Checker {

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    @Override
    public CheckType type() {
        return CheckType.HTTP;
    }

    @Override
    public CheckResult check(CheckDef def) {
        URI uri;
        try {
            uri = URI.create(def.target());
        } catch (IllegalArgumentException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "URL must be http or https");
        }

        boolean follow = def.configBool("followRedirects", true);
        boolean ignoreTls = def.configBool("ignoreTls", false);
        String keyword = def.configString("keyword", null);
        HttpClient client = clients.computeIfAbsent(follow + "/" + ignoreTls, k -> buildClient(follow, ignoreTls));

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(def.timeoutMs()))
                .header("User-Agent", "svcmon-hub")
                .GET();
        if (def.config().get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> {
                if (k != null && v != null && !k.toString().equalsIgnoreCase("host")) {
                    rb.header(k.toString(), v.toString());
                }
            });
        }

        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            String body = null;
            try (InputStream in = resp.body()) {
                if (keyword != null) {
                    body = new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
                }
            }
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            if (!statusMatches(def.configString("expectStatus", "200-299"), code)) {
                return CheckResult.of(def.id(), Status.DOWN, ms, null, "HTTP " + code + " not in expected range");
            }
            if (keyword != null && !body.contains(keyword)) {
                return CheckResult.of(def.id(), Status.DOWN, ms, null, "HTTP " + code + ", keyword not found");
            }
            String message = "HTTP " + code;
            Status status = Status.UP;
            Long certDays = certDaysLeft(resp);
            if (certDays != null) {
                int warnDays = (int) def.configDouble("certWarnDays", 14);
                if (certDays < 0) {
                    status = Status.DOWN;
                    message += ", certificate expired " + (-certDays) + "d ago";
                } else if (certDays < warnDays) {
                    status = Status.WARN;
                    message += ", certificate expires in " + certDays + "d";
                } else {
                    message += ", certificate valid " + certDays + "d";
                }
            }
            return CheckResult.of(def.id(), status, ms, null, message);
        } catch (java.net.http.HttpTimeoutException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Timed out after " + def.timeoutMs() + " ms");
        } catch (IOException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Interrupted");
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private static Long certDaysLeft(HttpResponse<?> resp) {
        return resp.sslSession().map(HttpChecker::leafNotAfterDays).orElse(null);
    }

    private static Long leafNotAfterDays(SSLSession session) {
        try {
            Certificate[] certs = session.getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                long ms = x509.getNotAfter().getTime() - System.currentTimeMillis();
                return Math.floorDiv(ms, 86_400_000L);
            }
        } catch (javax.net.ssl.SSLPeerUnverifiedException ignored) {
            // no peer certificate available
        }
        return null;
    }

    /** Matches a code against a spec like "200-299,301,404". */
    static boolean statusMatches(String spec, int code) {
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                int dash = p.indexOf('-');
                if (dash > 0) {
                    int lo = Integer.parseInt(p.substring(0, dash).trim());
                    int hi = Integer.parseInt(p.substring(dash + 1).trim());
                    if (code >= lo && code <= hi) {
                        return true;
                    }
                } else if (Integer.parseInt(p) == code) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // skip malformed segment
            }
        }
        return false;
    }

    private static HttpClient buildClient(boolean follow, boolean ignoreTls) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(follow ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        if (ignoreTls) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {new TrustAll()}, null);
                b.sslContext(ctx);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return b.build();
    }

    /** Accepts any certificate and hostname. Only used when a check sets ignoreTls explicitly. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
