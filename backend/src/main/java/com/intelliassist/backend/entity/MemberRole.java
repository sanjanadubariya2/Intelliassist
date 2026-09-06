package com.intelliassist.backend.entity;

/**
 * A member's role within one organization.
 *
 * <p>Three levels, ordered by privilege, with {@link #atLeast} as the only
 * comparison. A full permission matrix would be more flexible and much harder
 * to reason about; for this domain the questions are only ever "can they manage
 * the organization" and "can they change its content".
 *
 * <p>The role belongs to the membership, not the user: the same person can be
 * an OWNER in one organization and a MEMBER in another.
 */
public enum MemberRole {

    /** Full control, including billing and deleting the organization. */
    OWNER(3),

    /** Manages bots, knowledge and members. Cannot delete the organization. */
    ADMIN(2),

    /** Normal dashboard access: read, and use the bots. */
    MEMBER(1);

    private final int rank;

    MemberRole(int rank) {
        this.rank = rank;
    }

    /** True when this role is at least as privileged as {@code required}. */
    public boolean atLeast(MemberRole required) {
        return this.rank >= required.rank;
    }

    /** Spring Security convention: authorities are prefixed with {@code ROLE_}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
