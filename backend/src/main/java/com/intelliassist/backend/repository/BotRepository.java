package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.Bot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Bot persistence, scoped to an organization by construction.
 *
 * <p><strong>Every read takes an {@code organizationId}.</strong> There is
 * deliberately no {@code findById(UUID)} exposed for bots: if the only way to
 * load a bot is to name the tenant, then a cross-tenant read is not something a
 * developer can forget to guard — it is something they cannot express.
 *
 * <p>{@code JpaRepository} does inherit {@code findById}, so the convention is
 * enforced by review and by the isolation tests rather than by the compiler.
 * The service layer never calls it.
 */
public interface BotRepository extends JpaRepository<Bot, UUID> {

    /**
     * The safe lookup: a bot only exists if it belongs to this organization.
     *
     * <p>Returning empty for another tenant's id — rather than the row plus a
     * later permission check — means the caller cannot tell "not found" from
     * "not yours", so bot ids cannot be probed across organizations.
     */
    Optional<Bot> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<Bot> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    long countByOrganizationId(UUID organizationId);
}
