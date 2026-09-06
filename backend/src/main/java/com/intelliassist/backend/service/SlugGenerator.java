package com.intelliassist.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Turns an organization name into a URL-safe slug.
 *
 * <p>Slugs appear in URLs, so they must be lowercase, ASCII and hyphenated. The
 * interesting case is collisions: two customers can legitimately both be called
 * "Acme", and the second must still be able to sign up. A numeric suffix is
 * appended until the slug is free.
 */
public final class SlugGenerator {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private static final int MAX_LENGTH = 80;
    /** Leaves room for a "-999" suffix inside the column's 80 characters. */
    private static final int MAX_BASE_LENGTH = 70;
    private static final int MAX_ATTEMPTS = 1000;

    private SlugGenerator() {}

    /**
     * Produces a slug that {@code isTaken} reports as free.
     *
     * @param name    the organization name
     * @param isTaken returns true if a candidate slug already exists
     */
    public static String generate(String name, Predicate<String> isTaken) {
        String base = slugify(name);

        if (!isTaken.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix < MAX_ATTEMPTS; suffix++) {
            String candidate = base + "-" + suffix;
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }
        // A thousand organizations with the same name is not a real scenario;
        // failing loudly beats looping forever.
        throw new IllegalStateException("Could not generate a unique slug for: " + name);
    }

    /** The pure transformation, without the uniqueness check. */
    public static String slugify(String name) {
        // Decompose accents so "Café" becomes "cafe" rather than "caf".
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String ascii = COMBINING_MARKS.matcher(decomposed).replaceAll("");

        String slug = NON_ALPHANUMERIC.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = EDGE_HYPHENS.matcher(slug).replaceAll("");

        if (slug.length() > MAX_BASE_LENGTH) {
            slug = slug.substring(0, MAX_BASE_LENGTH);
            slug = EDGE_HYPHENS.matcher(slug).replaceAll("");
        }

        // A name of only punctuation or non-Latin script slugifies to nothing,
        // and the database CHECK constraint forbids an empty slug.
        return slug.isEmpty() ? "org" : slug;
    }

    public static int maxLength() {
        return MAX_LENGTH;
    }
}
