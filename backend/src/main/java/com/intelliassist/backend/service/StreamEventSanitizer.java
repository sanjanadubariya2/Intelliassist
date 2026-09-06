package com.intelliassist.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rewrites AI service events before they reach a browser.
 *
 * <p>Event <em>names</em> and semantics are preserved exactly, so a client
 * written against the AI contract still works. What changes is the payload:
 * two identifiers are removed, and upstream error text is replaced.
 *
 * <p><strong>Why this exists.</strong> The AI service's {@code metadata} event
 * carries {@code knowledge_base_id}, and every source carries a
 * {@code chunk_id}. Both are internal to a private service the browser must
 * never address — Phase 4 deliberately kept them out of REST responses, and
 * forwarding them here would reintroduce exactly the leak that avoided.
 * A {@code chunk_id} is also meaningless to a client: it changes whenever a
 * document is re-ingested.
 *
 * <p>Everything a UI genuinely needs — marker, document id, filename, page
 * number, score, snippet — is kept.
 */
@Component
public class StreamEventSanitizer {

    private static final Logger log = LoggerFactory.getLogger(StreamEventSanitizer.class);

    /** Internal to the AI service; never forwarded. */
    private static final String KNOWLEDGE_BASE_ID = "knowledge_base_id";

    private static final String CHUNK_ID = "chunk_id";
    private static final String QUERY_ID = "query_id";
    private static final String SOURCES = "sources";
    private static final String CITATIONS = "citations";

    private final ObjectMapper objectMapper;

    public StreamEventSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the event to forward, or {@code null} to drop it.
     *
     * <p>Tokens are by far the most common event and are passed through
     * untouched — parsing and re-serialising every one would add work per token
     * for a payload that contains nothing sensitive.
     */
    public AiStreamEvent sanitize(AiStreamEvent event) {
        if (event.is(AiStreamEvent.TOKEN) || event.is(AiStreamEvent.DONE)) {
            return event;
        }
        if (event.is(AiStreamEvent.ERROR)) {
            // Upstream error text can name the model and the provider. The
            // client gets wording we chose; the detail is already logged.
            return new AiStreamEvent(
                    AiStreamEvent.ERROR,
                    "{\"code\":\"AI_SERVICE_ERROR\","
                            + "\"message\":\"The assistant could not complete the answer.\"}");
        }
        if (event.is(AiStreamEvent.METADATA)) {
            return rewrite(event, this::stripMetadata);
        }
        if (event.is(AiStreamEvent.CITATIONS)) {
            return rewrite(event, this::stripCitations);
        }

        // An event name the contract does not define. Dropping is safer than
        // forwarding an unknown payload we have not inspected.
        log.warn("dropping unrecognised AI stream event '{}'", event.name());
        return null;
    }

    private AiStreamEvent rewrite(AiStreamEvent event, java.util.function.Consumer<ObjectNode> edit) {
        try {
            JsonNode parsed = objectMapper.readTree(event.data());
            if (!(parsed instanceof ObjectNode payload)) {
                return null;
            }
            edit.accept(payload);
            return new AiStreamEvent(event.name(), objectMapper.writeValueAsString(payload));
        } catch (Exception exception) {
            // Malformed JSON from upstream. Dropping the event loses metadata
            // but keeps the stream alive and leaks nothing; the answer itself
            // arrives in token events regardless.
            log.warn("dropping malformed AI stream event '{}'", event.name());
            return null;
        }
    }

    private void stripMetadata(ObjectNode payload) {
        payload.remove(KNOWLEDGE_BASE_ID);
        payload.remove(QUERY_ID);

        if (payload.get(SOURCES) instanceof ArrayNode sources) {
            sources.forEach(
                    source -> {
                        if (source instanceof ObjectNode node) {
                            node.remove(CHUNK_ID);
                        }
                    });
        }
    }

    private void stripCitations(ObjectNode payload) {
        if (payload.get(CITATIONS) instanceof ArrayNode citations) {
            citations.forEach(
                    citation -> {
                        if (citation instanceof ObjectNode node) {
                            node.remove(CHUNK_ID);
                        }
                    });
        }
    }
}
