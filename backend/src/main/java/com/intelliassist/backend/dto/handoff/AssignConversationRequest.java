package com.intelliassist.backend.dto.handoff;

import java.util.UUID;

/**
 * Hands a conversation to a teammate.
 *
 * <p>A null {@code assigneeId} returns it to the shared queue. The assignee must
 * belong to the caller's organization; that is checked server-side, never
 * trusted from here.
 */
public record AssignConversationRequest(UUID assigneeId) {}
