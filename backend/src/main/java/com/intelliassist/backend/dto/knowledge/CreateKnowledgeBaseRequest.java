package com.intelliassist.backend.dto.knowledge;

import jakarta.validation.constraints.Size;

/**
 * @param name optional; defaults to the bot's name when omitted
 */
public record CreateKnowledgeBaseRequest(
        @Size(max = 200, message = "Name must be at most 200 characters") String name) {}
