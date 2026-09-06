package com.intelliassist.backend.dto.bot;

import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.WidgetPosition;
import com.intelliassist.backend.entity.LauncherIcon;
import com.intelliassist.backend.entity.BotStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A bot as the dashboard API exposes it.
 *
 * <p>{@code organizationId} is included because the dashboard renders it, and
 * it is safe: a caller can only ever receive bots from their own organization,
 * so it tells them nothing they did not already know.
 */
public record BotResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        BotStatus status,
        String defaultLanguage,
        String systemPrompt,
        String primaryColor,
        WidgetPosition widgetPosition,
        String welcomeMessage,
        String fallbackMessage,
        LauncherIcon launcherIcon,
        Instant createdAt,
        Instant updatedAt) {

    public static BotResponse from(Bot bot) {
        return new BotResponse(
                bot.getId(),
                bot.getOrganization().getId(),
                bot.getName(),
                bot.getDescription(),
                bot.getStatus(),
                bot.getDefaultLanguage(),
                bot.getSystemPrompt(),
                bot.getPrimaryColor(),
                bot.getWidgetPosition(),
                bot.getWelcomeMessage(),
                bot.getFallbackMessage(),
                bot.getLauncherIcon(),
                bot.getCreatedAt(),
                bot.getUpdatedAt());
    }
}
