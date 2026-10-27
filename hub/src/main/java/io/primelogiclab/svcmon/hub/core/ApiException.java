package io.primelogiclab.svcmon.hub.core;

import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * A request problem the caller can fix; mapped to a JSON error with this status. {@code code} is the machine-readable
 * "error" of the body; it defaults to the status's name, and a specific one lets the dashboard tell cases apart.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, status.name().toLowerCase(Locale.ROOT), message);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
