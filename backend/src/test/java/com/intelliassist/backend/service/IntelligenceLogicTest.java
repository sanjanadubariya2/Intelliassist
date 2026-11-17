package com.intelliassist.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure parts of insights, health and handoff: no database, no Spring. */
class IntelligenceLogicTest {

    @Test
    void versionMarkersAreStrippedToFindTheSameDocument() {
        assertThat(KnowledgeHealthService.stem("Refund-Policy_v2.pdf"))
                .isEqualTo(KnowledgeHealthService.stem("refund policy v1.PDF"));
        assertThat(KnowledgeHealthService.stem("pricing-2024-final.docx"))
                .isEqualTo(KnowledgeHealthService.stem("pricing (1).docx"));
        assertThat(KnowledgeHealthService.stem("shipping.pdf"))
                .isNotEqualTo(KnowledgeHealthService.stem("returns.pdf"));
    }

    @Test
    void questionsDifferingOnlyInCaseAndPunctuationAreOne() {
        assertThat(ConversationInsightsService.normalise("  Do you SHIP to India?!  "))
                .isEqualTo(ConversationInsightsService.normalise("do you ship to india"));
        assertThat(ConversationInsightsService.normalise("¿Envían a México?"))
                .isEqualTo("envían a méxico");
    }

    @Test
    void medianIgnoresOneSlowOutlier() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        List<Object[]> rows =
                List.of(
                        new Object[] {t, t.plusSeconds(600)},
                        new Object[] {t, t.plusSeconds(1_200)},
                        new Object[] {t, t.plusSeconds(3 * 24 * 3_600)});
        assertThat(HandoffService.medianMinutes(rows)).isEqualTo(20L);
    }

    @Test
    void noResolvedHandoffsHasNoMedian() {
        assertThat(HandoffService.medianMinutes(List.of())).isNull();
    }
}
