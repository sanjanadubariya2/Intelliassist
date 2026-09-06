package com.intelliassist.backend.entity;

/**
 * Whether a conversation still accepts messages.
 *
 * <p>Closing is not deletion: the transcript stays readable for analytics and
 * for the customer reviewing what their bot said.
 */
public enum ConversationStatus {
    ACTIVE,
    CLOSED
}
