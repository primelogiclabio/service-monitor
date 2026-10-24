package dev.svcmon.hub.alerts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.AlertType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic webhook: POSTs a JSON description of the change. If the channel has a signing secret the body is signed
 * with HMAC-SHA256 and the signature sent as "X-Svcmon-Signature: sha256=<hex>", so the receiver can verify it.
 */
@Component
class WebhookSender implements AlertSender {

    private final HttpPoster poster;
    private final JsonMapper json;

    WebhookSender(HttpPoster poster, JsonMapper json) {
        this.poster = poster;
        this.json = json;
    }

    @Override
    public AlertType type() {
        return AlertType.WEBHOOK;
    }

    @Override
    public void send(AlertChannel channel, AlertEvent event, String hubId) throws AlertDeliveryException {
        Map<String, Object> config = channel.config();
        String body = json.writeValueAsString(AlertFormat.webhookPayload(event, hubId));

        Map<String, String> headers = new LinkedHashMap<>();
        if (config.get("headers") instanceof Map<?, ?> configured) {
            configured.forEach((k, v) -> {
                if (k != null && v != null) {
                    headers.put(k.toString(), v.toString());
                }
            });
        }
        Object secret = config.get("signingSecret");
        if (secret != null && !secret.toString().isBlank()) {
            headers.put("X-Svcmon-Signature", "sha256=" + sign(secret.toString(), body));
        }
        poster.postJson(String.valueOf(config.get("url")), body, headers);
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
