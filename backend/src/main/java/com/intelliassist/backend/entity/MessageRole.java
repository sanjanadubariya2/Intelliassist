package com.intelliassist.backend.entity;

/** Who produced a message. */
public enum MessageRole {

    /** A visitor or dashboard user asking something. */
    USER,

    /** The grounded answer, with its citations. */
    ASSISTANT,

    /**
     * Reserved for system notices stored in a transcript.
     *
     * <p>Not used to carry a prompt: the AI service owns its own grounded
     * system prompt and accepts no override, so there is nothing for this
     * application to send.
     */
    SYSTEM
}
