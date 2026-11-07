package io.primelogiclab.svcmon.hub.alerts;

import java.util.List;

/** A maintenance window ended: these alerts say what is not as it was when it opened. */
record MaintenanceEnded(List<AlertEvent> alerts) {
}
