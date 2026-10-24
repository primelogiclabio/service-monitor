package dev.svcmon.hub.alerts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

/** POSTs JSON to a webhook URL. Redirects are not followed, and error text never contains the URL (it is a secret). */
@Component
class HttpPoster {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    void postJson(String url, String json, Map<String, String> headers) throws AlertDeliveryException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url));
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
        } catch (HttpTimeoutException e) {
            throw new AlertDeliveryException("Timed out waiting for the receiver", true);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : ": " + AlertFormat.truncate(scrub(AlertFormat.oneLine(e.getMessage()), url), 200);
            throw new AlertDeliveryException(e.getClass().getSimpleName() + message, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AlertDeliveryException("Interrupted", false);
        }
    }

    static String scrub(String text, String url) {
        return url == null || url.isEmpty() ? text : text.replace(url, "<url>");
    }
}
