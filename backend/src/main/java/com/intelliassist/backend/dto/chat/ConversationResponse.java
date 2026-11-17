package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.ConversationStatus;
import com.intelliassist.backend.entity.User;
import java.time.Instant;
import java.util.UUID;

/**
 * A conversation as the dashboard sees it.
 *
 * <p>Must be built inside a transaction: the assignee is lazy, and the
 * application runs with {@code open-in-view} off.
 */
public record ConversationResponse(
        UUID id,
        UUID botId,
        String botName,
        String visitorId,
        String title,
        ConversationStatus status,
        ConversationResolution resolution,
        int messageCount,
        Instant startedAt,
        Instant lastMessageAt,
        UUID assigneeId,
        String assigneeName,
        Instant escalatedAt,
        Instant resolvedAt) {

    public static ConversationResponse from(Conversation conversation) {
        User assignee = conversation.getAssignee();
        return new ConversationResponse(
                conversation.getId(),
                conversation.getBot().getId(),
                conversation.getBot().getName(),
                conversation.getVisitorId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getResolution(),
                conversation.getMessageCount(),
                conversation.getStartedAt(),
                conversation.getLastMessageAt(),
                assignee == null ? null : assignee.getId(),
                assignee == null ? null : assignee.getName(),
                conversation.getEscalatedAt(),
                conversation.getResolvedAt());
    }
}
