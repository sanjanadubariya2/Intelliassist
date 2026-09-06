package com.intelliassist.backend.entity;

/**
 * Whether a bot will answer.
 *
 * <p>Two states only. An {@code INACTIVE} bot still exists with all its
 * knowledge intact — it simply stops serving. That is what a customer wants
 * while editing a knowledge base, and it is not the same as deletion.
 */
public enum BotStatus {
    ACTIVE,
    INACTIVE
}
