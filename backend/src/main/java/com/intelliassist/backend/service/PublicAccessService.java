package com.intelliassist.backend.service;

import com.intelliassist.backend.entity.AllowedDomain;
import com.intelliassist.backend.entity.ApiKey;
import com.intelliassist.backend.entity.BotStatus;
import com.intelliassist.backend.repository.AllowedDomainRepository;
import com.intelliassist.backend.repository.ApiKeyRepository;
import com.intelliassist.backend.security.ApiKeyHasher;
import com.intelliassist.backend.security.PublicBotAuthentication;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a widget request is allowed in.
 *
 * <p>Three gates, in order of cost — cheapest first, so a hostile caller cannot
 * make us do expensive work with a bad key.
 */
@Service
public class PublicAccessService {

    private static final Logger log = LoggerFactory.getLogger(PublicAccessService.class);

    /**
     * How stale {@code last_used_at} may get.
     *
     * <p>Writing it on every request would turn a read-only path into a write
     * on every visitor message, for a field nobody reads to the minute. Once an
     * hour is plenty to answer "is this key still in use".
     */
    private static final long LAST_USED_REFRESH_SECONDS = 3600;

    private final ApiKeyRepository apiKeyRepository;
    private final AllowedDomainRepository allowedDomainRepository;

    public PublicAccessService(
            ApiKeyRepository apiKeyRepository, AllowedDomainRepository allowedDomainRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.allowedDomainRepository = allowedDomainRepository;
    }

    /**
     * Authenticates a widget request.
     *
     * <p>Returns empty for every kind of failure — unknown key, revoked key,
     * inactive bot, disallowed origin. The caller turns that into one
     * indistinguishable {@code 401}: telling a prober <em>which</em> check
     * failed would let them enumerate valid keys and registered domains.
     */
    @Transactional
    public Optional<PublicBotAuthentication> authenticate(String rawKey, String origin) {
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(ApiKeyHasher.hash(rawKey));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ApiKey apiKey = found.get();
        if (!apiKey.isActive()) {
            log.debug("revoked key {} was used", apiKey.getId());
            return Optional.empty();
        }

        var bot = apiKey.getBot();
        if (bot.getStatus() != BotStatus.ACTIVE) {
            return Optional.empty();
        }

        if (!isOriginAllowed(bot.getId(), origin)) {
            log.debug("origin rejected for bot {}", bot.getId());
            return Optional.empty();
        }

        touchLastUsed(apiKey);

        return Optional.of(
                new PublicBotAuthentication(
                        bot.getId(), bot.getOrganization().getId(), apiKey.getId()));
    }

    /**
     * Whether this origin may use this bot.
     *
     * <p><strong>Fails closed.</strong> A bot with no registered domains
     * accepts nothing. That is deliberately inconvenient: the alternative —
     * treating "none configured" as "allow all" — would mean every newly
     * created key works from any website on the internet until someone
     * remembers to lock it down.
     */
    private boolean isOriginAllowed(UUID botId, String origin) {
        List<AllowedDomain> allowed = allowedDomainRepository.findAllByBotId(botId);
        if (allowed.isEmpty()) {
            return false;
        }

        String host = AllowedDomain.normalize(origin);
        if (host == null) {
            // No Origin and no Referer. A browser always sends one for a
            // cross-site request, so this is a non-browser caller.
            return false;
        }
        return allowed.stream().anyMatch(domain -> domain.getDomain().equals(host));
    }

    /** Throttled so a read path does not become a write on every message. */
    private void touchLastUsed(ApiKey apiKey) {
        Instant lastUsed = apiKey.getLastUsedAt();
        Instant now = Instant.now();

        if (lastUsed == null || lastUsed.plusSeconds(LAST_USED_REFRESH_SECONDS).isBefore(now)) {
            apiKey.markUsed(now);
        }
    }
}
