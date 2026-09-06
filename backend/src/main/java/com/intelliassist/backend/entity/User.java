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
 * A platform user.
 *
 * <p>A user is deliberately <em>not</em> owned by an organization. Membership is
 * a separate entity, so one person can belong to several organizations with a
 * different role in each — normal in B2B, and impossible to retrofit if the
 * organization id is welded onto the user.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * Always stored lowercase.
     *
     * <p>Normalising on write is what makes the plain unique constraint
     * genuinely case-insensitive, so {@code Alice@x.com} cannot register a
     * second account alongside {@code alice@x.com}.
     */
    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** BCrypt hash. Never logged, never serialised, never returned. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {
        // Required by JPA.
    }

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
    }

    /** Lowercase and trim. The single place email normalisation happens. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
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

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** Never includes the password hash, and never the email in full. */
    @Override
    public String toString() {
        return "User[id=%s]".formatted(id);
    }
}
