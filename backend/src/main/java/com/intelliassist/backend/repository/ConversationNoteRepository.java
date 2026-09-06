package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.ConversationNote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Internal notes, always scoped by organization.
 *
 * <p>No {@code findByConversationId}: a conversation id alone is not authority
 * to read staff commentary about a visitor. Every query joins through the
 * conversation's bot to its organization.
 */
public interface ConversationNoteRepository extends JpaRepository<ConversationNote, UUID> {

    @Query(
            """
            select note from ConversationNote note
            where note.conversation.id = :conversationId
              and note.conversation.bot.organization.id = :organizationId
            order by note.createdAt
            """)
    List<ConversationNote> findForConversation(
            @Param("conversationId") UUID conversationId,
            @Param("organizationId") UUID organizationId);
}
