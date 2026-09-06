package com.intelliassist.backend.dto.bot;

import com.intelliassist.backend.entity.LauncherIcon;
import com.intelliassist.backend.entity.WidgetPosition;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A change from the Customization screen.
 *
 * <p>Every field is optional and null means "leave it alone", so the screen can
 * save one section without blanking the others.
 *
 * @param primaryColor #RGB or #RRGGBB. Validated here as well as in the
 *     database, so the caller gets a field error rather than a constraint
 *     violation surfaced as a 500.
 */
public record BotAppearanceRequest(
        @Pattern(
                        regexp = "^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$",
                        message = "Primary color must be a hex colour such as #7C5AED")
                String primaryColor,
        WidgetPosition widgetPosition,
        @Size(max = 500, message = "Welcome message must be at most 500 characters")
                String welcomeMessage,
        @Size(max = 500, message = "Fallback message must be at most 500 characters")
                String fallbackMessage,
        LauncherIcon launcherIcon) {}
