package dev.svcmon.hub.model;

public record CheckResult(
        String checkId,
        long ts,
        Status status,
        Double latencyMs,
        Double value,
        String message) {

    public static CheckResult of(String checkId, Status status, Double latencyMs, Double value, String message) {
        return new CheckResult(checkId, System.currentTimeMillis(), status, latencyMs, value, message);
    }
}
