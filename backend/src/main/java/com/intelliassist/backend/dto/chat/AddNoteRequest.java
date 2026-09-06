package com.intelliassist.backend.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An operator's note on a conversation. */
public record AddNoteRequest(
        @NotBlank(message = "Note cannot be empty")
                @Size(max = 2000, message = "Note must be at most 2000 characters")
                String body) {}
