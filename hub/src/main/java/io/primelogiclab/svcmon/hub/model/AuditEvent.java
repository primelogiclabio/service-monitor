package io.primelogiclab.svcmon.hub.model;

/**
 * One line of the audit (see AuditService). {@code id} is 0 until stored; {@code network} is inside or outside, or
 * null when there was no request (the users reset command).
 */
public record AuditEvent(long id, long at, String category, String kind, String outcome, String actor,
                         String actorKind, String address, String network, String action, String target,
                         String detail) {
}
