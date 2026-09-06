package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Payload of the {@code citations} event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiStreamCitations(List<AiCitation> citations) {

    public List<AiCitation> orEmpty() {
        return citations == null ? List.of() : citations;
    }
}
