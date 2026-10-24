package dev.svcmon.hub.core;

import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.Status;

/** Published when a check's effective status changes. Alert channels listen for this. */
public record StatusChangedEvent(CheckDef check, Status from, Status to, String message, long ts) {
}
