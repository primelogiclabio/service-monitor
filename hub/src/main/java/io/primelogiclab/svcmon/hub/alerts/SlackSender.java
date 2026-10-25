package io.primelogiclab.svcmon.hub.alerts;

import java.util.Map;

import io.primelogiclab.svcmon.hub.model.AlertChannel;
import io.primelogiclab.svcmon.hub.model.AlertType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Slack incoming webhook (also works with Slack-compatible receivers such as Mattermost). */
@Component
class SlackSender implements AlertSender {

    private final HttpPoster poster;
    private final JsonMapper json;

    SlackSender(HttpPoster poster, JsonMapper json) {
        this.poster = poster;
        this.json = json;
    }

    @Override
    public AlertType type() {
        return AlertType.SLACK;
    }

    @Override
    public void send(AlertChannel channel, AlertEvent event, String hubId) throws AlertDeliveryException {
        poster.postJson(String.valueOf(channel.config().get("url")),
                json.writeValueAsString(AlertFormat.slackPayload(event)), Map.of());
    }
}
