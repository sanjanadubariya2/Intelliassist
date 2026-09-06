package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A customer company. This is the tenant boundary.
 *
 * <p>Every business resource — bots, documents, conversations — belongs to
 * exactly one organization, and every authorization decision reduces to whether
 * the caller's organization owns the row being touched.
 *
 * <p>No collection of members is mapped here on purpose. A {@code @OneToMany}
 * back to memberships invites loading an entire organization's users to answer
 * a question about one of them, and encourages accidental cascade deletes.
 * Repositories query the relationship explicitly instead.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    /** URL-safe identifier, unique across the system. Lowercase, hyphenated. */
    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Organization() {
        // Required by JPA.
    }

    public Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Identity by primary key.
     *
     * <p>The null-id guard matters: two unsaved entities would otherwise be
     * "equal" because both ids are null, which corrupts any Set they are put in
     * before a flush.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Organization that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        // Constant, not Objects.hash(id): the hash must not change when a
        // transient entity is persisted and acquires an id.
        return Objects.hashCode(getClass());
    }

    @Override
    public String toString() {
        return "Organization[id=%s, slug=%s]".formatted(id, slug);
    }
}
