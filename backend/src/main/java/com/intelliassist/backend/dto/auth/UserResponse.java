package com.intelliassist.backend.dto.auth;

import com.intelliassist.backend.entity.User;
import java.time.Instant;
import java.util.UUID;

/** A user as the API exposes them. Never carries the password hash. */
public record UserResponse(UUID id, String name, String email, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
