package io.primelogiclab.svcmon.hub.alerts;

import io.primelogiclab.svcmon.hub.model.ServerInfo;

/** Published when a server's agent stops reporting (online = false) or starts again (online = true). */
public record ServerStatusEvent(ServerInfo server, boolean online, String message, long ts) {
}
