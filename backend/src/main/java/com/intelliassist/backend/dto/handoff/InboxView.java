package com.intelliassist.backend.dto.handoff;

/** The slices of the handoff inbox. */
public enum InboxView {
    /** Every conversation currently waiting on a person. */
    OPEN,
    /** Waiting, and assigned to the caller. */
    MINE,
    /** Waiting, and nobody has picked it up. */
    UNASSIGNED,
    /** Escalated at some point, and since resolved or reopened. */
    CLOSED
}
