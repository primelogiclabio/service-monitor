package io.primelogiclab.svcmon.hub.alerts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * POSTs JSON to a webhook URL. Redirects are not followed, and error text never contains the URL (it is a secret),
 * only its host and port.
 */
@Component
class HttpPoster {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    void postJson(String url, String json, Map<String, String> headers) throws AlertDeliveryException {
        URI uri;
        HttpRequest.Builder request;
        try {
            uri = URI.create(url);
            request = HttpRequest.newBuilder(uri);
        } catch (IllegalArgumentException e) {
            throw new AlertDeliveryException("The channel's URL is not valid", false);
        }
        request.timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "svcmon-hub");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            try {
                request.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                throw new AlertDeliveryException("The channel has an invalid header '" + h.getKey() + "'", false);
            }
        }

        try {
            HttpResponse<String> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return;
            }
            String body = AlertFormat.truncate(scrub(AlertFormat.oneLine(response.body()), url), 200);
            throw new AlertDeliveryException("HTTP " + code + (body.isBlank() ? "" : ": " + body), code == 429 || code >= 500);
        } catch (IOException e) {
            throw new AlertDeliveryException(DeliveryFailure.webhook(e, uri), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AlertDeliveryException("Interrupted", false);
        }
    }

    static String scrub(String text, String url) {
        return url == null || url.isEmpty() ? text : text.replace(url, "<url>");
    }
}
