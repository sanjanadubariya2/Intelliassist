package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A credential letting an embedded widget talk to one bot.
 *
 * <p>Only the SHA-256 hash is stored. The raw key is returned exactly once, at
 * creation, and cannot be recovered afterwards - so a database dump does not
 * hand an attacker every customer's widget.
 *
 * <p>SHA-256 rather than BCrypt, deliberately. A password is low-entropy and
 * needs a slow hash to resist guessing; this key is 256 bits of randomness, so
 * brute force is already impossible and a slow hash would only add latency to
 * every public request.
 *
 * <p>Revocation is a timestamp, not a delete. Knowing when a key was turned off
 * matters when working out what happened, and the row is tiny.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false, updatable = false)
    private Bot bot;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String keyHash;

    /** Enough to identify a key in a list; useless as a credential. */
    @Column(name = "key_prefix", nullable = false, length = 16, updatable = false)
    private String keyPrefix;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {
        // Required by JPA.
    }

    public ApiKey(Bot bot, String name, String keyHash, String keyPrefix) {
        this.bot = bot;
        this.name = name;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    public void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApiKey that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** Never includes the hash. */
    @Override
    public String toString() {
        return "ApiKey[id=%s, prefix=%s, active=%s]".formatted(id, keyPrefix, isActive());
    }
}
