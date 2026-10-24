package dev.svcmon.hub.model;

public record CheckState(
        String checkId,
        Status status,
        int consecutiveFailures,
        long lastChange,
        Long lastResultAt,
        Double lastLatencyMs,
        Double lastValue,
        String lastMessage) {
}
