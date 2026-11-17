package com.intelliassist.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.entity.MessageCitation;
import com.intelliassist.backend.integration.ai.dto.AiCitation;
import com.intelliassist.backend.integration.ai.dto.AiStreamCitations;
import com.intelliassist.backend.integration.ai.dto.AiStreamDone;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import com.intelliassist.backend.integration.ai.dto.AiStreamMetadata;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the complete answer from a stream of events.
 *
 * <p>A streamed answer arrives in fragments, but the transcript must hold it
 * whole — six months later nobody cares that it was delivered incrementally.
 * This collects the pieces and reports whether the stream actually finished.
 *
 * <p>{@link #isComplete()} is the load-bearing part. Persistence happens only
 * when a {@code done} event was seen, so an interrupted stream leaves no
 * half-written answer in the transcript. A partial answer is worse than none:
 * it looks authoritative while being cut off mid-sentence, quite possibly
 * before the sentence that qualified it.
 *
 * <p>Not thread-safe, and does not need to be: one instance belongs to one
 * stream, and Reactor delivers that stream's events serially.
 */
public class StreamAccumulator {

    private static final Logger log = LoggerFactory.getLogger(StreamAccumulator.class);

    private final ObjectMapper objectMapper;
    private final StringBuilder answer = new StringBuilder();
    private final List<MessageCitation> citations = new ArrayList<>();

    private boolean complete;
    private boolean grounded;
    private String model;
    private int tokenEvents;

    public StreamAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Folds one event into the answer being rebuilt. */
    public void accept(AiStreamEvent event) {
        switch (event.name()) {
            case AiStreamEvent.METADATA -> readMetadata(event);
            case AiStreamEvent.TOKEN -> readToken(event);
            case AiStreamEvent.CITATIONS -> readCitations(event);
            case AiStreamEvent.DONE -> readDone(event);
            default -> {
                // An error event leaves `complete` false, which is exactly what
                // stops the partial answer being persisted.
            }
        }
    }

    private void readMetadata(AiStreamEvent event) {
        try {
            AiStreamMetadata metadata =
                    objectMapper.readValue(event.data(), AiStreamMetadata.class);
            if (metadata.model() != null) {
                this.model = metadata.model();
            }
            if (metadata.grounded() != null) {
                this.grounded = metadata.grounded();
            }
        } catch (Exception exception) {
            log.warn("could not parse stream metadata event");
        }
    }

    private void readToken(AiStreamEvent event) {
        try {
            var node = objectMapper.readTree(event.data());
            var text = node.get("text");
            if (text != null && !text.isNull()) {
                answer.append(text.asText());
                tokenEvents++;
            }
        } catch (Exception exception) {
            // One unreadable token is not worth failing the whole answer over;
            // the gap is preferable to discarding everything else.
            log.warn("could not parse stream token event");
        }
    }

    private void readCitations(AiStreamEvent event) {
        try {
            AiStreamCitations parsed =
                    objectMapper.readValue(event.data(), AiStreamCitations.class);

            citations.clear();
            for (AiCitation citation : parsed.orEmpty()) {
                citations.add(
                        new MessageCitation(
                                citation.marker(),
                                citation.documentId(),
                                citation.filename(),
                                citation.pageNumber(),
                                citation.snippet(),
                                citation.score()));
            }
        } catch (Exception exception) {
            log.warn("could not parse stream citations event");
        }
    }

    private void readDone(AiStreamEvent event) {
        try {
            AiStreamDone done = objectMapper.readValue(event.data(), AiStreamDone.class);
            this.grounded = done.groundedOrFalse();
        } catch (Exception exception) {
            log.warn("could not parse stream done event");
        }
        // Marked complete even if the payload was unreadable: the AI service
        // said the answer finished, and that is the fact that matters.
        this.complete = true;
    }

    /** True when a {@code done} event arrived, so the answer is whole. */
    public boolean isComplete() {
        return complete;
    }

    /** True when there is a finished, non-empty answer worth storing. */
    public boolean isPersistable() {
        return complete && !answer.toString().isBlank();
    }

    public String answer() {
        return answer.toString();
    }

    public List<MessageCitation> citations() {
        return List.copyOf(citations);
    }

    /**
     * Whether the answer actually came from a document.
     *
     * <p>The AI service reports grounded when retrieval cleared its threshold,
     * but the model can still decline ("I don't have information about
     * that"). Such an answer cites nothing, and counting it as grounded hid
     * every one of those gaps from analytics, insights and the health check.
     * An answer is grounded only if it cites a source.
     */
    public boolean grounded() {
        return grounded && !citations.isEmpty();
    }

    public String model() {
        return model;
    }

    public int tokenEvents() {
        return tokenEvents;
    }
}
