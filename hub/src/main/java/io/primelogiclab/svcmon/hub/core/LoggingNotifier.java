package io.primelogiclab.svcmon.hub.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Placeholder alert channel: writes status changes to the log. Real channels plug in as other listeners. */
@Component
public class LoggingNotifier {

    private static final Logger log = LoggerFactory.getLogger("svcmon.alerts");

    @EventListener
    public void onChange(StatusChangedEvent e) {
        log.info("{} {} -> {}: {} ({})", e.check().name(), e.from(), e.to(), e.message(), e.check().type());
    }
}
