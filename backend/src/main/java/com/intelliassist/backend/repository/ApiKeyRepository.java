package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * The public authentication lookup: hash to key, with its bot.
     *
     * <p>Fetches the bot in the same statement because every authenticated
     * public request needs it immediately - this is the hottest query on the
     * widget path.
     */
    @Query("""
            SELECT k FROM ApiKey k
            JOIN FETCH k.bot b
            JOIN FETCH b.organization
            WHERE k.keyHash = :keyHash
            """)
    Optional<ApiKey> findByKeyHash(@Param("keyHash") String keyHash);

    /** Scoped to the organization, so one tenant cannot list another's keys. */
    @Query("""
            SELECT k FROM ApiKey k
            JOIN k.bot b
            WHERE b.id = :botId AND b.organization.id = :organizationId
            ORDER BY k.createdAt DESC
            """)
    List<ApiKey> findAllByBotIdAndOrganizationId(
            @Param("botId") UUID botId, @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT k FROM ApiKey k
            JOIN FETCH k.bot b
            WHERE k.id = :keyId AND b.organization.id = :organizationId
            """)
    Optional<ApiKey> findByIdAndOrganizationId(
            @Param("keyId") UUID keyId, @Param("organizationId") UUID organizationId);
}
