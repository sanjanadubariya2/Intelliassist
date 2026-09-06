package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.chat.ChatExchangeResponse;
import com.intelliassist.backend.dto.chat.ConversationResponse;
import com.intelliassist.backend.dto.chat.MessageResponse;
import com.intelliassist.backend.dto.chat.SendMessageRequest;
import com.intelliassist.backend.dto.chat.StartConversationRequest;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.ChatService;
import com.intelliassist.backend.service.StreamingChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import com.intelliassist.backend.dto.chat.AddNoteRequest;
import com.intelliassist.backend.dto.chat.NoteResponse;
import com.intelliassist.backend.dto.chat.UpdateResolutionRequest;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PatchMapping;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Dashboard chat: start a conversation, send messages, read transcripts.
 *
 * <p>These are the authenticated routes, used by a logged-in dashboard user to
 * test a bot. The public widget gets its own namespace and its own
 * authentication in a later phase — a website visitor must never hold a
 * dashboard JWT.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ChatController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ChatService chatService;
    private final StreamingChatService streamingChatService;

    public ChatController(ChatService chatService, StreamingChatService streamingChatService) {
        this.chatService = chatService;
        this.streamingChatService = streamingChatService;
    }

    @PostMapping("/bots/{botId}/conversations")
    public ResponseEntity<ConversationResponse> startConversation(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody(required = false) StartConversationRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.startConversation(caller, botId, request));
    }

    @GetMapping("/bots/{botId}/conversations")
    public ResponseEntity<PageResponse<ConversationResponse>> listConversations(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        // Most recently active first: that is what someone reviewing their
        // bot's traffic wants to see.
        var pageable =
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt"));
        return ResponseEntity.ok(chatService.listConversations(caller, botId, pageable));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> getConversation(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID conversationId) {
        return ResponseEntity.ok(chatService.getConversation(caller, conversationId));
    }

    /**
     * Sends a question and returns both turns of the exchange.
     *
     * <p>Synchronous: the response arrives when the answer does. Streaming is a
     * separate endpoint in the next phase, for the case where a visitor should
     * see tokens appear rather than wait.
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ChatExchangeResponse> sendMessage(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatService.answer(caller, conversationId, request));
    }

    /**
     * The same exchange, streamed as Server-Sent Events.
     *
     * <p>Returns a {@link StreamingResponseBody}, which writes the SSE framing
     * explicitly on a container thread. See {@code StreamingChatService} for
     * why neither {@code Flux<ServerSentEvent>} nor {@code SseEmitter} is used
     * here. The application stays MVC end to end; {@code spring-webflux} is
     * present purely as an HTTP client for the outbound call.
     *
     * <p>Authorisation happens before the first byte is written, so an
     * unauthorised caller receives a normal {@code 404} rather than a
     * {@code 200} whose first event is an error.
     *
     * <p>Event names match the AI service's contract exactly - metadata,
     * token, citations, done, error - so a client written against one works
     * against the other. Payloads are sanitised: internal AI identifiers are
     * removed on the way through.
     */
    @PostMapping(
            value = "/conversations/{conversationId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamMessage(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request) {

        StreamingResponseBody body = streamingChatService.stream(caller, conversationId, request);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                // An SSE response must not be cached, and must not be buffered
                // by anything in front of it - nginx buffers by default, which
                // would hold every token back until the answer finished and
                // defeat the entire point. The AI service sets the same header
                // for the same reason.
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** The full transcript, oldest first. */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<List<MessageResponse>> listMessages(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID conversationId) {
        return ResponseEntity.ok(chatService.listMessages(caller, conversationId));
    }

    /** Closing keeps the transcript; it only stops further messages. */
    @PostMapping("/conversations/{conversationId}/close")
    public ResponseEntity<ConversationResponse> closeConversation(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID conversationId) {
        return ResponseEntity.ok(chatService.closeConversation(caller, conversationId));
    }

    // ------------------------------------------------- resolution and notes --

    /**
     * Records how a conversation turned out.
     *
     * <p>Separate from closing. Status is whether it is still open; resolution
     * is whether the visitor got what they came for, and a live conversation
     * can already be known to be escalated.
     */
    @Operation(summary = "Mark a conversation resolved, escalated, or unresolved")
    @PatchMapping("/conversations/{conversationId}/resolution")
    public ResponseEntity<ConversationResponse> resolve(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateResolutionRequest request) {

        return ResponseEntity.ok(
                chatService.resolve(caller, conversationId, request.resolution()));
    }

    @Operation(
            summary = "Internal notes on a conversation",
            description =
                    "Operator commentary. Never returned by the public API and never sent to "
                            + "the AI service.")
    @GetMapping("/conversations/{conversationId}/notes")
    public ResponseEntity<List<NoteResponse>> listNotes(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID conversationId) {
        return ResponseEntity.ok(chatService.listNotes(caller, conversationId));
    }

    @Operation(summary = "Add an internal note")
    @PostMapping("/conversations/{conversationId}/notes")
    public ResponseEntity<NoteResponse> addNote(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AddNoteRequest request) {

        NoteResponse note = chatService.addNote(caller, conversationId, request.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }
}
