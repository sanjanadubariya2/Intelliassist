package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.RagEvaluationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Evaluation runs, always scoped by organization.
 *
 * <p>There is deliberately no {@code findByBotId}. A bot id on its own is not
 * authority to read anything: every query here joins through the bot to its
 * organization, so a wrong tenant gets an empty result rather than a row
 * somebody forgot to check.
 */
public interface RagEvaluationRunRepository extends JpaRepository<RagEvaluationRun, UUID> {

    @Query(
            """
            select run from RagEvaluationRun run
            where run.bot.id = :botId
              and run.bot.organization.id = :organizationId
            order by run.finishedAt desc
            """)
    List<RagEvaluationRun> findLatest(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            Pageable pageable);

    /** The most recent run, which is what the dashboard shows. */
    default Optional<RagEvaluationRun> findMostRecent(UUID botId, UUID organizationId) {
        return findLatest(botId, organizationId, Pageable.ofSize(1)).stream().findFirst();
    }
}
