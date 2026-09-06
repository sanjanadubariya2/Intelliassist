package com.intelliassist.backend.dto.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param name a label so a customer can tell their keys apart */
public record CreateApiKeyRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name) {}
