package io.primelogiclab.svcmon.hub.alerts;

import java.util.Map;

import io.primelogiclab.svcmon.hub.model.AlertChannel;
import io.primelogiclab.svcmon.hub.model.AlertType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Discord webhook. Mentions are switched off so check names and agent messages cannot ping anyone. */
@Component
class DiscordSender implements AlertSender {

    private final HttpPoster poster;
    private final JsonMapper json;

    DiscordSender(HttpPoster poster, JsonMapper json) {
        this.poster = poster;
        this.json = json;
    }

    @Override
    public AlertType type() {
        return AlertType.DISCORD;
    }

    @Override
    public void send(AlertChannel channel, AlertEvent event, String hubId) throws AlertDeliveryException {
        poster.postJson(String.valueOf(channel.config().get("url")),
                json.writeValueAsString(AlertFormat.discordPayload(event)), Map.of());
    }
}
