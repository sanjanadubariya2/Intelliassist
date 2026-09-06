package com.intelliassist.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes public widget API keys.
 *
 * <p><strong>SHA-256, not BCrypt, and that is deliberate.</strong> BCrypt is
 * slow on purpose, to make guessing a low-entropy human password expensive.
 * These keys are 256 bits of {@link SecureRandom}, so guessing is already
 * impossible — a slow hash would add latency to every single widget request and
 * buy nothing. It would also prevent looking a key up by hash, forcing a scan of
 * every key in the table on each request.
 *
 * <p>The raw key exists only in the response that creates it. Nothing stores,
 * logs or can recover it afterwards.
 */
public final class ApiKeyHasher {

    /** Makes a leaked key recognisable in logs or a repository scan. */
    public static final String PREFIX = "ia_";

    private static final int KEY_BYTES = 32;
    private static final int PREFIX_LENGTH = 11;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {}

    /**
     * A newly minted key.
     *
     * @param raw    shown to the customer once and never again
     * @param hash   what gets stored
     * @param prefix a non-secret fragment, so keys can be told apart in a list
     */
    public record GeneratedKey(String raw, String hash, String prefix) {}

    public static GeneratedKey generate() {
        byte[] entropy = new byte[KEY_BYTES];
        RANDOM.nextBytes(entropy);

        // URL-safe and unpadded, so the key survives being pasted into a script
        // tag, a query string or a header without escaping.
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new GeneratedKey(raw, hash(raw), raw.substring(0, PREFIX_LENGTH));
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the platform; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Cheap shape check, so a malformed header never reaches the database. */
    public static boolean looksLikeKey(String candidate) {
        return candidate != null
                && candidate.startsWith(PREFIX)
                && candidate.length() >= PREFIX_LENGTH
                && candidate.length() <= 128;
    }
}
