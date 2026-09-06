package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.publicapi.PublicChatRequest;
import com.intelliassist.backend.dto.publicapi.PublicChatResponse;
import com.intelliassist.backend.security.PublicBotAuthentication;
import com.intelliassist.backend.service.PublicChatService;
import jakarta.validation.Valid;
import com.intelliassist.backend.dto.publicapi.PublicBotConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The widget API, for anonymous visitors on a customer's website.
 *
 * <p>Authenticated by {@code X-Bot-Key} plus an origin check, never by a
 * dashboard JWT. The principal is a {@link PublicBotAuthentication}, a
 * different type from the dashboard's — so a dashboard controller cannot
 * accidentally accept widget traffic, and this one cannot accept a JWT.
 *
 * <p><strong>No route takes a bot id.</strong> The bot comes from the API key.
 * There is no parameter a visitor could change to reach another tenant's bot,
 * or even another bot belonging to the same customer.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicChatController {

    /** Names the conversation a streamed answer belongs to. */
    public static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";

    private final PublicChatService publicChatService;

    public PublicChatController(PublicChatService publicChatService) {
        this.publicChatService = publicChatService;
    }

    /** First message: starts a conversation and answers in one call. */
    @PostMapping("/chat")
    public ResponseEntity<PublicChatResponse> chat(
            @AuthenticationPrincipal PublicBotAuthentication caller,
            @Valid @RequestBody PublicChatRequest request) {

        return ResponseEntity.ok(publicChatService.chat(caller, null, request));
    }

    /** Follow-up message in an existing conversation. */
    @PostMapping("/conversations/{conversationId}/chat")
    public ResponseEntity<PublicChatResponse> continueChat(
            @AuthenticationPrincipal PublicBotAuthentication caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody PublicChatRequest request) {

        return ResponseEntity.ok(publicChatService.chat(caller, conversationId, request));
    }

    /** Streamed first message. Same SSE contract as the dashboard endpoint. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamChat(
            @AuthenticationPrincipal PublicBotAuthentication caller,
            @Valid @RequestBody PublicChatRequest request) {

        return sse(publicChatService.stream(caller, null, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamContinued(
            @AuthenticationPrincipal PublicBotAuthentication caller,
            @PathVariable UUID conversationId,
            @Valid @RequestBody PublicChatRequest request) {

        return sse(publicChatService.stream(caller, conversationId, request));
    }

    private static ResponseEntity<StreamingResponseBody> sse(PublicChatService.StreamedChat streamed) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                // nginx buffers by default, which would hold every token back
                // until the answer finished.
                .header("X-Accel-Buffering", "no")
                // How a streaming client learns which conversation it is in.
                // The SSE events come from the AI service, which has no notion
                // of a conversation, so the id cannot travel in the body; as a
                // header it arrives ahead of the first token. Must stay listed
                // in the public CORS exposed headers or the browser hides it.
                .header(CONVERSATION_ID_HEADER, streamed.conversationId().toString())
                .body(streamed.body());
    }

    /**
     * The widget's appearance, fetched at load.
     *
     * <p>Lets the Customization screen actually change a live widget without
     * the customer editing their embed snippet. Falls back to the snippet's
     * data attributes if this call fails, so a blip cannot blank the widget.
     */
    @GetMapping("/config")
    public ResponseEntity<PublicBotConfigResponse> config(
            @AuthenticationPrincipal PublicBotAuthentication caller) {
        return ResponseEntity.ok(publicChatService.config(caller));
    }
}
