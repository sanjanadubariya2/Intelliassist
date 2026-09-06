package com.intelliassist.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors this application raises deliberately.
 *
 * <p>Carrying the status on the exception keeps the mapping next to the reason
 * it exists, and lets one handler translate the whole hierarchy. Nothing below
 * the web layer needs to know what an HTTP status is.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Stable machine-readable code. Clients branch on this, not on the message. */
    public String getCode() {
        return code;
    }
}
