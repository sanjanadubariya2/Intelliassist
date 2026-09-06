package com.intelliassist.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration creates a user, an organization and an OWNER membership in one
 * transaction. There is no "join an existing organization" path yet; invitations
 * are a later concern.
 *
 * @param name             the person's display name
 * @param email            login identifier, normalised to lowercase
 * @param password         plaintext, hashed immediately and never stored
 * @param organizationName the company they are signing up
 */
public record RegisterRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String email,

        // Length is the property that actually matters for password strength.
        // Composition rules push people toward "Password1!" and no further.
        @NotBlank(message = "Password is required")
        @Size(min = 12, max = 128, message = "Password must be between 12 and 128 characters")
        String password,

        @NotBlank(message = "Organization name is required")
        @Size(max = 200, message = "Organization name must be at most 200 characters")
        String organizationName) {}
