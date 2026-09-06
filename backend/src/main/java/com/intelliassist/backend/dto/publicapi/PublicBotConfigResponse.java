package com.intelliassist.backend.dto.publicapi;

import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.LauncherIcon;
import com.intelliassist.backend.entity.WidgetPosition;

/**
 * What the widget is allowed to know about its bot.
 *
 * <p>Appearance and nothing else. Deliberately not the dashboard's
 * {@code BotResponse}: that carries the organization id, the system prompt and
 * timestamps, none of which a visitor's browser has any business holding.
 *
 * @param name shown in the widget header, so it is public by definition
 */
public record PublicBotConfigResponse(
        String name,
        String primaryColor,
        WidgetPosition position,
        String welcomeMessage,
        String fallbackMessage,
        LauncherIcon launcherIcon,
        String defaultLanguage) {

    public static PublicBotConfigResponse from(Bot bot) {
        return new PublicBotConfigResponse(
                bot.getName(),
                bot.getPrimaryColor(),
                bot.getWidgetPosition(),
                bot.getWelcomeMessage(),
                bot.getFallbackMessage(),
                bot.getLauncherIcon(),
                bot.getDefaultLanguage());
    }
}
