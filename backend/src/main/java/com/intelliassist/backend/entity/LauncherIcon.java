package com.intelliassist.backend.entity;

/**
 * The glyph on the widget's launcher button.
 *
 * <p>A fixed set rather than a URL: an arbitrary image would be a request the
 * widget makes from a customer's page to a host we do not control, which is
 * both a privacy leak and a way to break the launcher with a 404.
 */
public enum LauncherIcon {
    CHAT,
    MESSAGE,
    HELP,
    SPARKLE
}
