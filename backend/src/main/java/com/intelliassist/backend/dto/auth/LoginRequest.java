package com.intelliassist.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Login deliberately applies no size or format rules beyond presence.
 *
 * <p>Rejecting a malformed email here with a different message than a failed
 * lookup would leak which addresses have accounts.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required") String email,
        @NotBlank(message = "Password is required") String password) {

    /** Never print the password. */
    @Override
    public String toString() {
        return "LoginRequest[email=***, password=***]";
    }
}
