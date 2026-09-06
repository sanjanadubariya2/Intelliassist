package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.UsageRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    Optional<UsageRecord> findByBotIdAndUsageDate(UUID botId, LocalDate usageDate);

    /**
     * Creates today's bucket if it does not exist, doing nothing if it does.
     *
     * <p>Two concurrent messages would otherwise both find no row, both insert,
     * and one would fail the unique constraint — turning a successful answer
     * into a 500 for the sake of a counter. {@code ON CONFLICT DO NOTHING}
     * makes the create idempotent, so the increment that follows always has a
     * row to work with.
     *
     * <p>Native SQL because JPQL has no upsert; this is a Postgres feature and
     * the application is not portable to another database anyway.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO usage_records (bot_id, usage_date)
                    VALUES (:botId, :usageDate)
                    ON CONFLICT (bot_id, usage_date) DO NOTHING
                    """,
            nativeQuery = true)
    void ensureBucket(@Param("botId") UUID botId, @Param("usageDate") LocalDate usageDate);

    /**
     * Applies increments in the database rather than by read-modify-write.
     *
     * <p>Loading the row, adding one and saving would lose counts whenever two
     * requests overlap. {@code column = column + :delta} is atomic, so a busy
     * bot counts correctly without any locking on our side.
     */
    @Modifying
    @Query(
            value =
                    """
                    UPDATE usage_records
                       SET conversation_count = conversation_count + :conversations,
                           message_count      = message_count + :messages,
                           ungrounded_count   = ungrounded_count + :ungrounded,
                           token_count        = token_count + :tokens,
                           updated_at         = now()
                     WHERE bot_id = :botId AND usage_date = :usageDate
                    """,
            nativeQuery = true)
    void increment(
            @Param("botId") UUID botId,
            @Param("usageDate") LocalDate usageDate,
            @Param("conversations") int conversations,
            @Param("messages") int messages,
            @Param("ungrounded") int ungrounded,
            @Param("tokens") long tokens);

    /** A bot's daily rows over a range, scoped to the owning organization. */
    @Query("""
            SELECT u FROM UsageRecord u
            JOIN u.bot b
            WHERE b.id = :botId
              AND b.organization.id = :organizationId
              AND u.usageDate BETWEEN :from AND :to
            ORDER BY u.usageDate
            """)
    List<UsageRecord> findRange(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

}
