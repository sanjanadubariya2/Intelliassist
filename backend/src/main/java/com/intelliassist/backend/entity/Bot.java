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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A configurable assistant belonging to one organization.
 *
 * <p>A bot owns no knowledge itself — that arrives in Phase 4 as a
 * {@code KnowledgeBase} holding a reference to the AI service's own knowledge
 * base. The split matters: this row is application state (who owns it, is it
 * enabled, what is it called), while everything about retrieval lives on the
 * other side of the HTTP boundary.
 */
@Entity
@Table(name = "bots")
public class Bot {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * The owning tenant.
     *
     * <p>Immutable: a bot cannot be moved between organizations. Allowing it
     * would mean every cached authorization decision could become wrong, for a
     * feature nobody asked for.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BotStatus status;

    @Column(name = "default_language", nullable = false, length = 10)
    private String defaultLanguage;

    /**
     * Reserved for per-bot prompt customisation.
     *
     * <p>Currently stored and never sent: the AI service's frozen contract
     * accepts no system prompt on a query. It is kept because the field is
     * cheap, the intent is real, and prepending it to the query text would be
     * actively harmful — the query string is what gets embedded for retrieval,
     * so padding it with instructions would degrade search quality.
     */
    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    /**
     * Widget appearance.
     *
     * <p>Columns on the bot rather than a side table: exactly one row per bot,
     * always read with the bot, never queried alone. A join table would add a
     * join to the widget's hot path and buy nothing.
     */
    @Column(name = "primary_color", nullable = false, length = 7)
    private String primaryColor = "#7C5AED";

    @Enumerated(EnumType.STRING)
    @Column(name = "widget_position", nullable = false, length = 20)
    private WidgetPosition widgetPosition = WidgetPosition.BOTTOM_RIGHT;

    @Column(name = "welcome_message", nullable = false, length = 500)
    private String welcomeMessage = "Hi there! 👋 How can I help you today?";

    /** Wording only. The AI service decides <em>when</em> to abstain. */
    @Column(name = "fallback_message", nullable = false, length = 500)
    private String fallbackMessage =
            "Sorry, I don't have information about that. Would you like to talk to a human?";

    @Enumerated(EnumType.STRING)
    @Column(name = "launcher_icon", nullable = false, length = 20)
    private LauncherIcon launcherIcon = LauncherIcon.CHAT;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Bot() {
        // Required by JPA.
    }

    public Bot(Organization organization, String name, String description, String defaultLanguage) {
        this.organization = organization;
        this.name = name;
        this.description = description;
        this.defaultLanguage = defaultLanguage;
        this.status = BotStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BotStatus getStatus() {
        return status;
    }

    public void setStatus(BotStatus status) {
        this.status = status;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
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
        if (!(other instanceof Bot that)) {
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
        return "Bot[id=%s, status=%s]".formatted(id, status);
    }

    // ---------------------------------------------------------- appearance --

    public String getPrimaryColor() {
        return primaryColor;
    }

    public WidgetPosition getWidgetPosition() {
        return widgetPosition;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public String getFallbackMessage() {
        return fallbackMessage;
    }

    public LauncherIcon getLauncherIcon() {
        return launcherIcon;
    }

    /**
     * Applies an appearance change.
     *
     * <p>Null means "leave this one alone", so a partial update from the
     * Customization screen cannot blank the fields it did not send.
     */
    public void updateAppearance(
            String primaryColor,
            WidgetPosition widgetPosition,
            String welcomeMessage,
            String fallbackMessage,
            LauncherIcon launcherIcon) {
        if (primaryColor != null) {
            this.primaryColor = primaryColor;
        }
        if (widgetPosition != null) {
            this.widgetPosition = widgetPosition;
        }
        if (welcomeMessage != null) {
            this.welcomeMessage = welcomeMessage;
        }
        if (fallbackMessage != null) {
            this.fallbackMessage = fallbackMessage;
        }
        if (launcherIcon != null) {
            this.launcherIcon = launcherIcon;
        }
    }
}
