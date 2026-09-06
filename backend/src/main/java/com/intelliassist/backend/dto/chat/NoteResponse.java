package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.ConversationNote;
import java.time.Instant;
import java.util.UUID;

/** An internal note. Never returned by the public API. */
public record NoteResponse(
        UUID id, UUID authorId, String authorName, String body, Instant createdAt) {

    public static NoteResponse from(ConversationNote note) {
        return new NoteResponse(
                note.getId(),
                note.getAuthorId(),
                note.getAuthorName(),
                note.getBody(),
                note.getCreatedAt());
    }
}
