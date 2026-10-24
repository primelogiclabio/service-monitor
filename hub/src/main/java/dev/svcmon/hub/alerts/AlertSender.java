package dev.svcmon.hub.alerts;

import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.AlertType;

/** Delivers one alert to one channel of one type. Throws {@link AlertDeliveryException} when it did not work. */
interface AlertSender {

    AlertType type();

    void send(AlertChannel channel, AlertEvent event, String hubId) throws AlertDeliveryException;
}
