package dev.svcmon.hub.alerts;

/** A delivery that did not work. {@code retryable} is true for network trouble and server-side errors. */
public class AlertDeliveryException extends Exception {

    private final boolean retryable;

    public AlertDeliveryException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
