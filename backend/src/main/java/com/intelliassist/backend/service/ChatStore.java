package com.intelliassist.backend.service;

import java.util.ArrayList;
import org.springframework.data.domain.PageRequest;
import com.intelliassist.backend.integration.ai.dto.AiConversationTurn;
import com.intelliassist.backend.dto.chat.ChatExchangeResponse;
import com.intelliassist.backend.dto.chat.ConversationResponse;
import com.intelliassist.backend.dto.chat.MessageResponse;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.BotStatus;
import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.Message;
import com.intelliassist.backend.entity.MessageCitation;
import com.intelliassist.backend.exception.ApiException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.MessageRepository;
import java.time.Instant;
import java.util.List;
import com.intelliassist.backend.dto.chat.NoteResponse;
import com.intelliassist.backend.entity.ConversationNote;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.ConversationNoteRepository;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of chat.
 *
 * <p>Separate from {@link ChatService} for the same reason as
 * {@link KnowledgeStore}: a RAG query is a slow HTTP call, and no database
 * transaction may be held across it. Each method here is one short transaction,
 * and every return value is a DTO so nothing lazy escapes the session.
 */
@Service
public class ChatStore {

    private final BotRepository botRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationNoteRepository noteRepository;

    public ChatStore(
            BotRepository botRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConversationNoteRepository noteRepository) {
        this.botRepository = botRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.noteRepository = noteRepository;
    }

    // ---------------------------------------------------------- conversations --

    @Transactional
    public ConversationResponse startConversation(
            UUID botId, UUID organizationId, String visitorId) {

        Bot bot =
                botRepository
                        .findByIdAndOrganizationId(botId, organizationId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Bot", botId));

        requireActive(bot);
        return ConversationResponse.from(
                conversationRepository.save(new Conversation(bot, blankToNull(visitorId))));
    }

    /**
     * Everything the chat path needs before calling the AI service, in one
     * transaction: the conversation is ours, the bot is usable, and where its
     * corpus lives.
     */
    @Transactional(readOnly = true)
    public ChatContext loadContext(UUID conversationId, UUID organizationId) {
        Conversation conversation =
                conversationRepository
                        .findByIdAndOrganizationId(conversationId, organizationId)
                        .orElseThrow(
                                () ->
                                        ResourceNotFoundException.of(
                                                "Conversation", conversationId));

        if (!conversation.isActive()) {
            throw new ConversationClosedException();
        }
        requireActive(conversation.getBot());

        UUID aiKnowledgeBaseId =
                knowledgeBaseRepository
                        .findByBotIdAndOrganizationId(conversation.getBot().getId(), organizationId)
                        .map(kb -> kb.getAiKnowledgeBaseId())
                        .orElseThrow(NoKnowledgeBaseException::new);

        return new ChatContext(conversation.getId(), conversation.getBot().getId(), aiKnowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID conversationId, UUID organizationId) {
        return ConversationResponse.from(requireConversation(conversationId, organizationId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> listConversations(
            UUID botId, UUID organizationId, Pageable pageable) {
        return PageResponse.from(
                conversationRepository.findAllByBotIdAndOrganizationId(
                        botId, organizationId, pageable),
                ConversationResponse::from);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> listMessages(UUID conversationId, UUID organizationId) {
        // Authorises the conversation before returning any of its content.
        requireConversation(conversationId, organizationId);

        return messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(MessageResponse::from)
                .toList();
    }

    @Transactional
    public ConversationResponse closeConversation(UUID conversationId, UUID organizationId) {
        Conversation conversation = requireConversation(conversationId, organizationId);
        conversation.close();
        return ConversationResponse.from(conversation);
    }

    // --------------------------------------------------------------- messages --

    /**
     * Persists both turns of an exchange in one transaction.
     *
     * <p>Written together on purpose: a stored question with no answer, or an
     * answer with no question, is a transcript that cannot be read. The AI call
     * has already completed by the time this runs, so the transaction stays
     * short.
     */
    @Transactional
    public ChatExchangeResponse recordExchange(
            UUID conversationId,
            String question,
            String answer,
            List<MessageCitation> citations,
            boolean grounded,
            String model,
            int totalTokens,
            int latencyMs) {

        Conversation conversation =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        ResourceNotFoundException.of(
                                                "Conversation", conversationId));

        Message userMessage = messageRepository.save(Message.user(conversation, question));
        Message assistantMessage =
                messageRepository.save(
                        Message.assistant(
                                conversation,
                                answer,
                                citations,
                                grounded,
                                model,
                                totalTokens,
                                latencyMs));

        conversation.titleFromFirstMessage(question);
        conversation.recordMessages(2, Instant.now());

        return new ChatExchangeResponse(
                MessageResponse.from(userMessage), MessageResponse.from(assistantMessage));
    }

    // ------------------------------------------------- resolution and notes --

    /**
     * Records how a conversation turned out.
     *
     * <p>Does not close it: a visitor may keep typing after an operator has
     * judged the outcome, and forcing the two together would either reopen
     * resolved cases or silence live ones.
     */
    @Transactional
    public ConversationResponse resolve(
            UUID conversationId, UUID organizationId, ConversationResolution resolution) {

        Conversation conversation = requireConversation(conversationId, organizationId);
        conversation.resolveAs(resolution);
        return ConversationResponse.from(conversation);
    }

    /** Adds an operator's note. Scoped through the conversation, as always. */
    @Transactional
    public NoteResponse addNote(
            UUID conversationId, UUID organizationId, User author, String body) {

        Conversation conversation = requireConversation(conversationId, organizationId);
        ConversationNote note = new ConversationNote(conversation, author, body.strip());
        return NoteResponse.from(noteRepository.save(note));
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> listNotes(UUID conversationId, UUID organizationId) {
        // Confirms ownership before returning staff commentary about a visitor.
        requireConversation(conversationId, organizationId);

        return noteRepository.findForConversation(conversationId, organizationId).stream()
                .map(NoteResponse::from)
                .toList();
    }

    // -------------------------------------------------------------- internals --

    private Conversation requireConversation(UUID conversationId, UUID organizationId) {
        return conversationRepository
                .findByIdAndOrganizationId(conversationId, organizationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));
    }

    private static void requireActive(Bot bot) {
        if (bot.getStatus() != BotStatus.ACTIVE) {
            throw new BotInactiveException();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ----------------------------------------------------------------- values --

    /** What the chat path needs to reach the AI service. */

    /**
     * The most recent turns of a conversation, oldest first.
     *
     * <p>Sent to the AI service so a follow-up ("what about part-timers?") can
     * be rewritten into a standalone search query. Read-only and its own
     * transaction: it must not extend the one around persistence, and it never
     * runs while an HTTP call to the AI service is in flight.
     *
     * <p>Scoped by organization like every other read here — a conversation id
     * alone is not authority to read its contents.
     */
    @Transactional(readOnly = true)
    public List<AiConversationTurn> recentTurns(UUID conversationId, UUID organizationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        // Ownership is re-checked rather than assumed from the caller's earlier
        // load: this method is public and cheap to misuse.
        conversationRepository
                .findByIdAndOrganizationId(conversationId, organizationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));

        List<Message> recent =
                messageRepository.findByConversationIdOrderByCreatedAtDesc(
                        conversationId, PageRequest.of(0, limit));

        // Newest-first from the database, reversed here: the rewriter reads a
        // conversation the way a person does.
        List<AiConversationTurn> turns = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            turns.add(AiConversationTurn.of(message.getRole(), message.getContent()));
        }
        return turns;
    }

    public record ChatContext(UUID conversationId, UUID botId, UUID aiKnowledgeBaseId) {}

    /** The bot exists but has been switched off. */
    public static class BotInactiveException extends ApiException {
        public BotInactiveException() {
            super(HttpStatus.CONFLICT, "BOT_INACTIVE", "This bot is not currently active");
        }
    }

    /** The conversation was closed and accepts no further messages. */
    public static class ConversationClosedException extends ApiException {
        public ConversationClosedException() {
            super(
                    HttpStatus.CONFLICT,
                    "CONVERSATION_CLOSED",
                    "This conversation has been closed");
        }
    }

    /**
     * The bot has no corpus.
     *
     * <p>A distinct, actionable error rather than an empty answer: "upload a
     * document" is something the customer can act on, whereas "I don't have
     * information about that" would look like the bot simply failed.
     */
    public static class NoKnowledgeBaseException extends ApiException {
        public NoKnowledgeBaseException() {
            super(
                    HttpStatus.CONFLICT,
                    "NO_KNOWLEDGE_BASE",
                    "This bot has no documents yet. Upload a document before chatting.");
        }
    }
}
