package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.chat.SendMessageRequest;
import com.intelliassist.backend.dto.publicapi.PublicChatRequest;
import com.intelliassist.backend.dto.publicapi.PublicChatResponse;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.security.PublicBotAuthentication;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.dto.publicapi.PublicBotConfigResponse;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.repository.BotRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat for anonymous website visitors.
 *
 * <p>Reuses {@link ChatService} and {@link StreamingChatService} rather than
 * duplicating the RAG flow. Two implementations of "ask a bot a question" would
 * drift, and one of them would be the one nobody remembers to fix.
 *
 * <p>The bridge between the two worlds is {@link #asDashboardCaller}: the
 * services below expect an {@link AuthenticatedUser}, so the widget's
 * organization is wrapped into one for the duration of the call. This is
 * <em>not</em> a privilege grant — see that method for why it is safe.
 *
 * <p>Every method takes a {@link PublicBotAuthentication}, so a caller cannot
 * name a bot: it is fixed by the API key.
 */
@Service
public class PublicChatService {

    private static final Logger log = LoggerFactory.getLogger(PublicChatService.class);

    private final ChatService chatService;
    private final StreamingChatService streamingChatService;
    private final ChatStore store;
    private final PublicRateLimiter rateLimiter;
    private final UsageService usageService;
    private final BotRepository botRepository;

    public PublicChatService(
            ChatService chatService,
            StreamingChatService streamingChatService,
            ChatStore store,
            PublicRateLimiter rateLimiter,
            UsageService usageService,
            BotRepository botRepository) {
        this.chatService = chatService;
        this.streamingChatService = streamingChatService;
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.usageService = usageService;
        this.botRepository = botRepository;
    }

    /**
     * Starts a conversation, or answers within an existing one.
     *
     * <p>A visitor sends a message and gets an answer; whether a conversation
     * already existed is our bookkeeping, not theirs.
     */
    public PublicChatResponse chat(
            PublicBotAuthentication caller, UUID conversationId, PublicChatRequest request) {

        rateLimiter.check(caller.apiKeyId());

        UUID target = conversationId != null
                ? requireOwnConversation(caller, conversationId)
                : startConversation(caller, request.visitorId());

        var exchange =
                chatService.answer(
                        asDashboardCaller(caller), target, new SendMessageRequest(request.content()));

        log.info("public chat answered for bot {} (conversation {})", caller.botId(), target);
        return PublicChatResponse.from(target, exchange.assistantMessage());
    }

    /**
     * The same exchange, streamed.
     *
     * <p>Returns the conversation id alongside the body. A streamed first
     * message creates a conversation, but the SSE events cannot carry its id:
     * they are a pass-through from the AI service, which has no concept of a
     * conversation. Without this a widget would open a new conversation for
     * every message it streams -- no thread, no context, and a conversation
     * count that counts turns. The controller returns it as a response header,
     * which reaches the client before the first token and is present even if
     * the stream then fails.
     */
    public StreamedChat stream(
            PublicBotAuthentication caller, UUID conversationId, PublicChatRequest request) {

        rateLimiter.check(caller.apiKeyId());

        UUID target = conversationId != null
                ? requireOwnConversation(caller, conversationId)
                : startConversation(caller, request.visitorId());

        StreamingResponseBody body =
                streamingChatService.stream(
                        asDashboardCaller(caller), target, new SendMessageRequest(request.content()));

        return new StreamedChat(target, body);
    }

    /** A streaming answer, and the conversation it belongs to. */
    public record StreamedChat(UUID conversationId, StreamingResponseBody body) {}

    /**
     * Starts a widget conversation and counts it.
     *
     * <p>The count is applied here rather than inherited, because this path
     * goes straight to {@link ChatStore} instead of through
     * {@link ChatService#startConversation}. Omitting it left widget
     * conversations entirely uncounted — and widget traffic is most of the
     * traffic, so the usage report showed messages against zero conversations.
     */
    public UUID startConversation(PublicBotAuthentication caller, String visitorId) {
        UUID conversationId =
                store.startConversation(caller.botId(), caller.organizationId(), visitorId).id();

        usageService.recordConversationStarted(caller.botId());
        return conversationId;
    }

    /**
     * Confirms a conversation belongs to <em>this</em> bot.
     *
     * <p>Organization scoping alone is not enough here. A customer with two
     * bots would otherwise be able to use one bot's public key to read and
     * continue the other's conversations — same tenant, but not the same
     * audience, and the second bot may be embedded on a different site.
     */
    private UUID requireOwnConversation(PublicBotAuthentication caller, UUID conversationId) {
        var conversation = store.getConversation(conversationId, caller.organizationId());

        if (!conversation.botId().equals(caller.botId())) {
            // Reported as "not found", like every other cross-boundary access.
            throw ResourceNotFoundException.of("Conversation", conversationId);
        }
        return conversation.id();
    }

    /**
     * Adapts a widget principal into the shape the chat services expect.
     *
     * <p><strong>Not a privilege escalation.</strong> The role is MEMBER, the
     * lowest there is, and every chat operation requires only MEMBER anyway.
     * The organization comes from the API key, which is server-side data — not
     * from anything the visitor sent. So this widens nothing: it is a type
     * adapter, and the security decision was already made by the filter.
     *
     * <p>The alternative — threading a second principal type through
     * ChatService — would duplicate the RAG flow for no gain in safety.
     */
    private static AuthenticatedUser asDashboardCaller(PublicBotAuthentication caller) {
        return new AuthenticatedUser(
                // No user id: nobody is logged in. The value is never read on
                // the chat path, and a null would risk an NPE far from here.
                caller.apiKeyId(),
                caller.organizationId(),
                MemberRole.MEMBER,
                null);
    }

    /**
     * The widget's own configuration.
     *
     * <p>The bot is fixed by the API key, so there is no id for a caller to
     * substitute. Returns appearance only — see PublicBotConfigResponse.
     */
    public PublicBotConfigResponse config(PublicBotAuthentication caller) {
        Bot bot =
                botRepository
                        .findByIdAndOrganizationId(caller.botId(), caller.organizationId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Bot", caller.botId()));

        return PublicBotConfigResponse.from(bot);
    }
}
