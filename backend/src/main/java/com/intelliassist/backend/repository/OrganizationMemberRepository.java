package com.intelliassist.backend.repository;

import com.intelliassist.backend.dto.auth.MemberResponse;

import com.intelliassist.backend.entity.OrganizationMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    /**
     * The authorization query: does this user belong to this organization, and
     * with what role.
     *
     * <p>Both associations are fetched in one statement. Without the joins this
     * runs on every authenticated request and then lazy-loads the user and
     * organization separately - three queries where one will do.
     */
    @Query("""
            SELECT m FROM OrganizationMember m
            JOIN FETCH m.user
            JOIN FETCH m.organization
            WHERE m.user.id = :userId AND m.organization.id = :organizationId
            """)
    Optional<OrganizationMember> findMembership(
            @Param("userId") UUID userId, @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT m FROM OrganizationMember m
            JOIN FETCH m.organization
            WHERE m.user.id = :userId
            ORDER BY m.createdAt
            """)
    List<OrganizationMember> findAllByUserId(@Param("userId") UUID userId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    /**
     * Everyone in an organization, for the Members screen.
     *
     * <p>Projected into a DTO in the query rather than mapped afterwards, so
     * the password hash never leaves the database. Selecting the entity and
     * trusting a later mapping step to drop it is how that leaks.
     */
    @Query("""
            SELECT new com.intelliassist.backend.dto.auth.MemberResponse(
                m.user.id, m.user.name, m.user.email, m.role, m.createdAt)
            FROM OrganizationMember m
            WHERE m.organization.id = :organizationId
            ORDER BY m.createdAt
            """)
    List<MemberResponse> findMembers(@Param("organizationId") UUID organizationId);
}
