package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Joins a user to an organization with a role.
 *
 * <p>This is the entity authorization actually runs on. A request is permitted
 * when a membership exists linking the authenticated user to the organization
 * that owns the resource — so a missing membership is a {@code 403}, and the
 * check is the same shape everywhere.
 *
 * <p>Loading is {@code LAZY} on both sides. Authorization usually needs only the
 * role and the ids, and eager loading would fetch the whole user and
 * organization on every single authenticated request.
 */
@Entity
@Table(
        name = "organization_members",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_members_org_user",
                        columnNames = {"organization_id", "user_id"}))
public class OrganizationMember {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * Stored as a string, not an ordinal.
     *
     * <p>{@code EnumType.ORDINAL} writes the enum's position, so reordering the
     * constants silently reassigns everyone's role. The database column has a
     * matching CHECK constraint.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected OrganizationMember() {
        // Required by JPA.
    }

    public OrganizationMember(Organization organization, User user, MemberRole role) {
        this.organization = organization;
        this.user = user;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public User getUser() {
        return user;
    }

    public MemberRole getRole() {
        return role;
    }

    public void setRole(MemberRole role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OrganizationMember that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    @Override
    public String toString() {
        return "OrganizationMember[id=%s, role=%s]".formatted(id, role);
    }
}
