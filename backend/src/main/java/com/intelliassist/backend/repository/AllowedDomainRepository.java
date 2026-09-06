package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.AllowedDomain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllowedDomainRepository extends JpaRepository<AllowedDomain, UUID> {

    /** Every origin a bot accepts. Read on each public request. */
    List<AllowedDomain> findAllByBotId(UUID botId);

    boolean existsByBotIdAndDomain(UUID botId, String domain);

    @Query("""
            SELECT d FROM AllowedDomain d
            JOIN d.bot b
            WHERE b.id = :botId AND b.organization.id = :organizationId
            ORDER BY d.domain
            """)
    List<AllowedDomain> findAllByBotIdAndOrganizationId(
            @Param("botId") UUID botId, @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT d FROM AllowedDomain d
            JOIN d.bot b
            WHERE d.id = :domainId AND b.organization.id = :organizationId
            """)
    Optional<AllowedDomain> findByIdAndOrganizationId(
            @Param("domainId") UUID domainId, @Param("organizationId") UUID organizationId);
}
