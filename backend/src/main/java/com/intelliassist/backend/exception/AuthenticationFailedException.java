package com.intelliassist.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Bad credentials, or a token that is missing, malformed or expired.
 *
 * <p>The message is deliberately identical for "no such email" and "wrong
 * password". Distinguishing them turns the login endpoint into an oracle for
 * which email addresses have accounts.
 */
public class AuthenticationFailedException extends ApiException {

    public AuthenticationFailedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("Invalid email or password");
    }
}
