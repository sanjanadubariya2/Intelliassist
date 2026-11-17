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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Links a provider account (Google, GitHub, LinkedIn) to a user.
 *
 * <p>Identified by the provider's stable subject id, not by email. An address
 * can change hands at the provider; the subject never does.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private IdentityProvider provider;

    @Column(nullable = false, updatable = false, length = 255)
    private String subject;

    @Column(length = 320)
    private String email;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected UserIdentity() {
        // Required by JPA.
    }

    public UserIdentity(User user, IdentityProvider provider, String subject, String email) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
        this.email = User.normalizeEmail(email);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserIdentity that)) {
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
        return "UserIdentity[id=%s, provider=%s]".formatted(id, provider);
    }
}
