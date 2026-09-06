package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A website permitted to embed a bot.
 *
 * <p>Second line of defence behind the API key. A key pasted into a public web
 * page is readable by anyone who views source, so on its own it cannot be the
 * whole story - the origin check means a stolen key is only usable from the
 * customer's own site.
 *
 * <p>Stored as a bare lowercase hostname so the request-time comparison is a
 * plain equality check. Normalisation happens once, here, rather than being
 * repeated on every request.
 */
@Entity
@Table(name = "allowed_domains")
public class AllowedDomain {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false, updatable = false)
    private Bot bot;

    @Column(nullable = false, length = 253)
    private String domain;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AllowedDomain() {
        // Required by JPA.
    }

    public AllowedDomain(Bot bot, String domain) {
        this.bot = bot;
        this.domain = normalize(domain);
    }

    /**
     * Reduces user input or an Origin header to a bare hostname.
     *
     * <p>Accepts "https://example.com", "example.com:8080" and "Example.com"
     * and yields "example.com" for all three, so what a customer types and what
     * a browser sends compare equal.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.strip().toLowerCase(Locale.ROOT);
        try {
            if (candidate.contains("://")) {
                String host = URI.create(candidate).getHost();
                if (host != null) {
                    return host;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Not a URI; fall through and treat it as a bare host.
        }
        // Drop a port and anything after a path separator.
        candidate = candidate.split("/", 2)[0];
        int colon = candidate.lastIndexOf(':');
        return colon > 0 ? candidate.substring(0, colon) : candidate;
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public String getDomain() {
        return domain;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AllowedDomain that)) {
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
        return "AllowedDomain[id=%s, domain=%s]".formatted(id, domain);
    }
}
