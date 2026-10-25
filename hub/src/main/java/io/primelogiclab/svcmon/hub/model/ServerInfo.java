package io.primelogiclab.svcmon.hub.model;

public record ServerInfo(
        String id,
        String name,
        String cluster,
        String os,
        String agentVersion,
        Long lastSeen,
        long createdAt) {
}
