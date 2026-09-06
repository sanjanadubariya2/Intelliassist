package com.intelliassist.backend.service;

import java.util.List;
import com.intelliassist.backend.integration.ai.dto.AiConversationTurn;
import com.intelliassist.backend.config.AiServiceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.dto.chat.SendMessageRequest;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Streams an answer, then persists it once it is whole.
 *
 * <p><strong>Why {@link StreamingResponseBody} and not a {@code Flux} or an
 * {@code SseEmitter}.</strong> This is a Spring MVC application. Returning
 * {@code Flux<ServerSentEvent>} is the WebFlux idiom and MVC does not unwrap
 * it, so event names end up buried inside JSON. {@code SseEmitter} does frame
 * correctly, but it is completed from whichever thread the reactive pipeline
 * happens to be on — and when the whole answer arrives in milliseconds, that
 * completion can race the container's async initialisation and truncate the
 * response before the terminating chunk is written. {@code StreamingResponseBody}
 * has neither problem: the container owns the thread, and the framing is
 * written explicitly.
 *
 * <p>The cost is one container thread held for the life of a stream. That is a
 * real ceiling — Tomcat's default pool bounds concurrent streams — and it is
 * the right trade here: predictable behaviour and code that can be read in one
 * sitting, rather than a reactive rewrite of an otherwise blocking application.
 *
 * <p>Three ordering rules, each load-bearing:
 *
 * <ol>
 *   <li><strong>Authorise before anything is written.</strong> A caller with no
 *       access gets an ordinary {@code 404}, not a {@code 200} whose first
 *       event says it failed.
 *   <li><strong>Wait for the first upstream event before returning.</strong>
 *       Until then the status line is still ours, so an AI service failure is a
 *       real HTTP error rather than an in-band one.
 *   <li><strong>Persist only after {@code done}.</strong> A transcript holding
 *       half an answer is worse than one holding none: it reads as
 *       authoritative while being cut off.
 * </ol>
 */
@Service
public class StreamingChatService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    private static final String ERROR_PAYLOAD =
            "{\"code\":\"AI_SERVICE_ERROR\","
                    + "\"message\":\"The assistant could not complete the answer.\"}";

    /** One event at a time: the client consumes as fast as the model produces. */
    private static final int PREFETCH = 1;

    private final ChatStore store;
    private final AiServiceClient aiServiceClient;
    private final StreamEventSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final UsageService usageService;
    private final AiServiceProperties properties;

    public StreamingChatService(
            ChatStore store,
            AiServiceClient aiServiceClient,
            StreamEventSanitizer sanitizer,
            ObjectMapper objectMapper,
            UsageService usageService,
            AiServiceProperties properties) {
        this.store = store;
        this.aiServiceClient = aiServiceClient;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.usageService = usageService;
        this.properties = properties;
    }

    public StreamingResponseBody stream(
            AuthenticatedUser caller, UUID conversationId, SendMessageRequest request) {

        // Blocking, and deliberately before the response is committed.
        ChatStore.ChatContext context = store.loadContext(conversationId, caller.organizationId());

        // Read before the stream opens, so no database work happens while the
        // AI service is streaming back to us.
        List<AiConversationTurn> history =
                store.recentTurns(
                        conversationId, caller.organizationId(), properties.historyTurns());
        String question = request.content().strip();

        // toStream gives a closeable view of the Flux: closing it cancels the
        // subscription, which closes the connection to the AI service.
        Stream<AiStreamEvent> upstream =
                aiServiceClient
                        .streamQuery(context.aiKnowledgeBaseId(), question, history)
                        .toStream(PREFETCH);

        Iterator<AiStreamEvent> events = upstream.iterator();
        boolean hasFirst;
        try {
            // Blocks until the AI service produces something or fails. Doing it
            // here rather than inside the response body is what lets a failure
            // become a real status code: nothing has been written yet.
            hasFirst = events.hasNext();
        } catch (RuntimeException exception) {
            upstream.close();
            throw exception;
        }

        long startedAt = System.nanoTime();
        boolean firstAvailable = hasFirst;

        return outputStream ->
                writeStream(
                        outputStream, upstream, events, firstAvailable, context, question, startedAt);
    }

    private void writeStream(
            OutputStream outputStream,
            Stream<AiStreamEvent> upstream,
            Iterator<AiStreamEvent> events,
            boolean hasFirst,
            ChatStore.ChatContext context,
            String question,
            long startedAt) {

        StreamAccumulator accumulator = new StreamAccumulator(objectMapper);
        boolean clientGone = false;

        try {
            boolean more = hasFirst;
            while (more) {
                AiStreamEvent event = events.next();
                accumulator.accept(event);

                if (!send(outputStream, sanitizer.sanitize(event))) {
                    clientGone = true;
                    break;
                }
                more = events.hasNext();
            }
        } catch (RuntimeException exception) {
            // The stream broke after it had started. The status line is long
            // gone, so the only way to report it is in-band.
            log.warn(
                    "stream failed mid-answer for conversation {}: {}",
                    context.conversationId(),
                    exception.getClass().getSimpleName());
            send(outputStream, new AiStreamEvent(AiStreamEvent.ERROR, ERROR_PAYLOAD));
        } finally {
            // Cancels the upstream subscription, so the AI service stops
            // generating tokens nobody will read.
            upstream.close();
        }

        if (clientGone) {
            log.info(
                    "client disconnected from stream for conversation {} after {} tokens",
                    context.conversationId(),
                    accumulator.tokenEvents());
        }

        persistIfComplete(context, question, accumulator, startedAt);
    }

    /** Writes one SSE event. Returns false when the client has gone away. */
    private boolean send(OutputStream outputStream, AiStreamEvent event) {
        if (event == null) {
            return true;
        }
        try {
            String frame = "event: " + event.name() + "\ndata: " + event.data() + "\n\n";
            outputStream.write(frame.getBytes(StandardCharsets.UTF_8));
            // Flushed per event: buffering would defeat the entire point of
            // streaming, since the client would receive everything at the end.
            outputStream.flush();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Writes the exchange, but only if the answer actually finished.
     *
     * <p>A disconnected stream can still be persistable: if the client vanished
     * after {@code done} arrived, the answer is whole and worth keeping.
     *
     * <p>Runs on the container thread that served the stream, which is already
     * blocked and about to be released — so a blocking write here costs
     * nothing extra and needs no scheduler.
     */
    private void persistIfComplete(
            ChatStore.ChatContext context,
            String question,
            StreamAccumulator accumulator,
            long startedAt) {

        if (!accumulator.isPersistable()) {
            return;
        }

        int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
        try {
            store.recordExchange(
                    context.conversationId(),
                    question,
                    accumulator.answer(),
                    accumulator.citations(),
                    accumulator.grounded(),
                    accumulator.model(),
                    // Token usage is not reported over the stream; the
                    // synchronous endpoint is where accounting figures come from.
                    0,
                    latencyMs);

            // Streamed answers report no token usage, so this contributes to
            // the message and grounding counts only.
            usageService.recordExchange(context.botId(), accumulator.grounded(), 0);

            log.info(
                    "persisted streamed answer for conversation {} ({} tokens, grounded={})",
                    context.conversationId(),
                    accumulator.tokenEvents(),
                    accumulator.grounded());

        } catch (RuntimeException exception) {
            // The answer already reached the client; failing to record it is
            // worth an alert but not worth breaking the response over.
            log.error(
                    "could not persist streamed answer for conversation {}",
                    context.conversationId(),
                    exception);
        }
    }
}
