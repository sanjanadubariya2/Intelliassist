package com.intelliassist.backend.entity;

/**
 * Whether the visitor got what they came for.
 *
 * <p>Deliberately separate from {@link ConversationStatus}. Status is whether
 * the conversation is still open; this is whether it went well. A conversation
 * can be CLOSED and ESCALATED at once, and one column could not say so.
 */
public enum ConversationResolution {
    /** Nobody has judged it yet. The default, and the honest one. */
    UNRESOLVED,

    /** The bot answered, or an operator marked it handled. */
    RESOLVED,

    /** Handed to a human, either on request or because the bot could not answer. */
    ESCALATED
}
