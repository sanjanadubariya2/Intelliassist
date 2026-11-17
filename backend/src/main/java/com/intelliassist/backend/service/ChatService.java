package com.intelliassist.backend.service;

import com.intelliassist.backend.integration.ai.dto.AiConversationTurn;
import com.intelliassist.backend.config.AiServiceProperties;
import com.intelliassist.backend.dto.chat.ChatExchangeResponse;
import com.intelliassist.backend.dto.chat.ConversationResponse;
import com.intelliassist.backend.dto.chat.MessageResponse;
import com.intelliassist.backend.dto.chat.SendMessageRequest;
import com.intelliassist.backend.dto.chat.StartConversationRequest;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.entity.MessageCitation;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.dto.AiCitation;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.util.List;
import com.intelliassist.backend.dto.chat.NoteResponse;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Conversations and the synchronous RAG exchange.
 *
 * <p>The flow for one message:
 *
 * <pre>
 *   authorise -> load context -> ask the AI service -> persist both turns
 * </pre>
 *
 * <p>Recent turns <em>are</em> sent to the AI service, but only so it can
 * rewrite a follow-up into a standalone search query — it stores none of them
 * and still holds no state between calls. What is deliberately never done is
 * composing history into the query string itself: that drags the embedding
 * toward the previous topic and makes retrieval worse, which is the whole
 * reason rewriting exists. See docs/QUERY_REWRITING.md.
 *
 * <p>This class holds no transaction. {@link ChatStore} owns them, and each is
 * short — a RAG query can take seconds, and holding a pooled connection across
 * it would exhaust the pool under load.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final AiServiceClient aiServiceClient;
    private final AiServiceProperties properties;
    private final UsageService usageService;
    private final UserRepository userRepository;

    public ChatService(
            ChatStore store,
            AiServiceClient aiServiceClient,
            UsageService usageService,
            AiServiceProperties properties,
            UserRepository userRepository) {
        this.properties = properties;
        this.store = store;
        this.aiServiceClient = aiServiceClient;
        this.usageService = usageService;
        this.userRepository = userRepository;
    }

    public ConversationResponse startConversation(
            AuthenticatedUser caller, UUID botId, StartConversationRequest request) {

        String visitorId = request == null ? null : request.visitorId();
        var conversation = store.startConversation(botId, caller.organizationId(), visitorId);

        usageService.recordConversationStarted(botId);
        return conversation;
    }

    public ConversationResponse getConversation(AuthenticatedUser caller, UUID conversationId) {
        return store.getConversation(conversationId, caller.organizationId());
    }

    public PageResponse<ConversationResponse> listConversations(
            AuthenticatedUser caller, UUID botId, Pageable pageable) {
        return store.listConversations(botId, caller.organizationId(), pageable);
    }

    public List<MessageResponse> listMessages(AuthenticatedUser caller, UUID conversationId) {
        return store.listMessages(conversationId, caller.organizationId());
    }

    public ConversationResponse closeConversation(AuthenticatedUser caller, UUID conversationId) {
        return store.closeConversation(conversationId, caller.organizationId());
    }

    /**
     * Sends a question and returns the grounded answer.
     *
     * <p><strong>Only the current question is sent.</strong> The AI service's
     * contract has no conversation parameter, and the obvious workaround —
     * concatenating recent turns into the query — would actively hurt: that
     * string is embedded for vector search, so padding it with earlier
     * questions dilutes the search vector and retrieves worse passages. A real
     * fix is query rewriting (an extra model call that turns "what about
     * part-time staff?" into a standalone question), which belongs in the AI
     * service where the model already lives.
     *
     * <p>The practical consequence: follow-up questions that depend on earlier
     * context will retrieve poorly. This is a known limitation, recorded in
     * {@code AI_INTEGRATION_CONTRACT.md} section 6.
     *
     * <p>If the AI call fails, nothing is persisted. Storing a question whose
     * answer never arrived would leave a transcript that looks like the bot
     * ignored the user.
     */
    public ChatExchangeResponse answer(
            AuthenticatedUser caller, UUID conversationId, SendMessageRequest request) {

        ChatStore.ChatContext context =
                store.loadContext(conversationId, caller.organizationId());

        String question = request.content().strip();

        // Read before the AI call, in its own short transaction. The turns are
        // the ones already persisted, so the current question is not among
        // them — which is correct: it is the thing being rewritten.
        List<AiConversationTurn> history =
                store.recentTurns(
                        conversationId, caller.organizationId(), properties.historyTurns());

        long startedAt = System.nanoTime();
        AiRagQueryResponse response =
                aiServiceClient.query(context.aiKnowledgeBaseId(), question, history);
        int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);

        List<MessageCitation> citations = toCitations(response);
        // Grounded only if it cites a source: the AI service says grounded
        // when retrieval cleared its threshold, but the model may still have
        // declined, and an uncited refusal is a gap in the corpus, not an answer.
        boolean grounded = response.grounded() && !citations.isEmpty();

        if (!grounded) {
            // A normal outcome, not a failure: retrieval found nothing relevant
            // and the AI service declined without calling a model. Recording it
            // is what makes gaps in the corpus visible later.
            log.info(
                    "ungrounded answer for conversation {} (candidates={})",
                    conversationId,
                    response.usage() == null ? 0 : response.usage().candidatesRetrieved());
        }
        if (response.truncated()) {
            log.warn(
                    "answer truncated for conversation {}; trailing citations may be missing",
                    conversationId);
        }

        ChatExchangeResponse exchange =
                store.recordExchange(
                        context.conversationId(),
                        question,
                        response.answer(),
                        citations,
                        grounded,
                        response.model(),
                        response.totalTokens(),
                        latencyMs);

        // After persistence, and best-effort: a counter must never be the
        // reason a customer loses an answer they already paid a model call for.
        usageService.recordExchange(context.botId(), grounded, response.totalTokens());

        log.info(
                "answered conversation {} in {}ms (grounded={}, citations={})",
                conversationId,
                latencyMs,
                response.grounded(),
                exchange.assistantMessage().citations().size());

        return exchange;
    }

    /**
     * Converts AI citations into the form stored with the message.
     *
     * <p>The chunk id is dropped: it is meaningful only inside the AI service's
     * index, would be stale the moment a document is re-ingested, and nothing
     * on our side can do anything with it. Everything a reader needs — file,
     * page, snippet, marker — is kept.
     */
    private static List<MessageCitation> toCitations(AiRagQueryResponse response) {
        return response.citationsOrEmpty().stream().map(ChatService::toCitation).toList();
    }

    private static MessageCitation toCitation(AiCitation citation) {
        return new MessageCitation(
                citation.marker(),
                citation.documentId(),
                citation.filename(),
                citation.pageNumber(),
                citation.snippet(),
                citation.score());
    }

    // ------------------------------------------------- resolution and notes --

    /** Records how a conversation turned out. Does not close it. */
    public ConversationResponse resolve(
            AuthenticatedUser caller, UUID conversationId, ConversationResolution resolution) {
        return store.resolve(conversationId, caller.organizationId(), resolution);
    }

    /**
     * Adds an operator's note.
     *
     * <p>The author is looked up from the principal rather than accepted from
     * the request: a caller must not be able to sign someone else's name to a
     * note about a customer.
     */
    public NoteResponse addNote(AuthenticatedUser caller, UUID conversationId, String body) {
        User author =
                userRepository
                        .findById(caller.userId())
                        .orElseThrow(() -> ResourceNotFoundException.of("User", caller.userId()));

        return store.addNote(conversationId, caller.organizationId(), author, body);
    }

    public List<NoteResponse> listNotes(AuthenticatedUser caller, UUID conversationId) {
        return store.listNotes(conversationId, caller.organizationId());
    }
}
