package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.ConversationResolution;
import jakarta.validation.constraints.NotNull;

/** Records how a conversation turned out. */
public record UpdateResolutionRequest(
        @NotNull(message = "Resolution is required") ConversationResolution resolution) {}
