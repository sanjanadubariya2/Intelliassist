package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID botId,
        String visitorId,
        String title,
        ConversationStatus status,
        ConversationResolution resolution,
        int messageCount,
        Instant startedAt,
        Instant lastMessageAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getBot().getId(),
                conversation.getVisitorId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getResolution(),
                conversation.getMessageCount(),
                conversation.getStartedAt(),
                conversation.getLastMessageAt());
    }
}
