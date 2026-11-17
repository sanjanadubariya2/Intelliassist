package com.intelliassist.backend.security.oauth;

/**
 * A single sign-on attempt that cannot complete.
 *
 * <p>Never turned into an API error body: the browser is mid-redirect, so the
 * controller sends it back to the login page with {@link #code()} in the query
 * string, where the dashboard explains it.
 */
public class OAuthException extends RuntimeException {

    private final String code;

    public OAuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** A short machine-readable reason, e.g. {@code no_verified_email}. */
    public String code() {
        return code;
    }
}
