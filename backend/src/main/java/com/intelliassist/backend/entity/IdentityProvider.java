package com.intelliassist.backend.entity;

import java.util.Locale;
import java.util.Optional;

/**
 * An external identity provider a user can sign in with.
 *
 * <p>Stored by name ({@code EnumType.STRING}), and the database constrains the
 * column to these values, so adding one needs a migration as well as a new
 * constant here.
 */
public enum IdentityProvider {
    GOOGLE("Google"),
    GITHUB("GitHub"),
    LINKEDIN("LinkedIn");

    private final String displayName;

    IdentityProvider(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The lowercase id used in URLs and configuration, e.g. {@code github}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a URL id. Empty for anything unknown, rather than throwing. */
    public static Optional<IdentityProvider> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (IdentityProvider provider : values()) {
            if (provider.id().equals(id.toLowerCase(Locale.ROOT))) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
