package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.publicapi.AllowedDomainResponse;
import com.intelliassist.backend.dto.publicapi.ApiKeyResponse;
import com.intelliassist.backend.dto.publicapi.CreatedApiKeyResponse;
import com.intelliassist.backend.entity.AllowedDomain;
import com.intelliassist.backend.entity.ApiKey;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.exception.AccessDeniedException;
import com.intelliassist.backend.exception.DuplicateResourceException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.AllowedDomainRepository;
import com.intelliassist.backend.repository.ApiKeyRepository;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.security.ApiKeyHasher;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard management of widget credentials: API keys and allowed domains.
 *
 * <p>These are the levers a customer pulls to put a bot on their website, so
 * they are ADMIN-only. Handing a MEMBER the ability to mint a public credential
 * would make the role distinction meaningless.
 */
@Service
public class WidgetCredentialService {

    private static final Logger log = LoggerFactory.getLogger(WidgetCredentialService.class);

    private final BotRepository botRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AllowedDomainRepository allowedDomainRepository;

    public WidgetCredentialService(
            BotRepository botRepository,
            ApiKeyRepository apiKeyRepository,
            AllowedDomainRepository allowedDomainRepository) {
        this.botRepository = botRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.allowedDomainRepository = allowedDomainRepository;
    }

    // ------------------------------------------------------------ api keys --

    /**
     * Mints a key and returns it in full — the only time that ever happens.
     *
     * <p>The raw value is generated, hashed, and the hash stored. Nothing
     * retains the original, so this response is the customer's single chance to
     * copy it.
     */
    @Transactional
    public CreatedApiKeyResponse createApiKey(AuthenticatedUser caller, UUID botId, String name) {
        requireRole(caller, MemberRole.ADMIN);
        Bot bot = requireBot(caller, botId);

        var generated = ApiKeyHasher.generate();
        ApiKey saved =
                apiKeyRepository.save(
                        new ApiKey(bot, name.strip(), generated.hash(), generated.prefix()));

        // The prefix is safe to log; the key itself never is.
        log.info(
                "created API key {} ({}...) for bot {}",
                saved.getId(),
                generated.prefix(),
                botId);

        return CreatedApiKeyResponse.of(ApiKeyResponse.from(saved), generated.raw());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(AuthenticatedUser caller, UUID botId) {
        requireBot(caller, botId);
        return apiKeyRepository
                .findAllByBotIdAndOrganizationId(botId, caller.organizationId())
                .stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    /**
     * Revokes a key. The row stays, with a timestamp.
     *
     * <p>Keeping it means a customer can still see that a key existed and when
     * it was turned off, which is what you want when working out how a leak
     * happened.
     */
    @Transactional
    public void revokeApiKey(AuthenticatedUser caller, UUID keyId) {
        requireRole(caller, MemberRole.ADMIN);

        ApiKey apiKey =
                apiKeyRepository
                        .findByIdAndOrganizationId(keyId, caller.organizationId())
                        .orElseThrow(() -> ResourceNotFoundException.of("API key", keyId));

        apiKey.revoke();
        log.info("revoked API key {}", keyId);
    }

    // ------------------------------------------------------------- domains --

    @Transactional
    public AllowedDomainResponse addDomain(
            AuthenticatedUser caller, UUID botId, String rawDomain) {

        requireRole(caller, MemberRole.ADMIN);
        Bot bot = requireBot(caller, botId);

        String domain = AllowedDomain.normalize(rawDomain);
        if (domain == null || domain.isBlank() || !domain.contains(".")) {
            throw new com.intelliassist.backend.exception.InvalidRequestException(
                    "'%s' is not a valid domain".formatted(rawDomain));
        }
        if (allowedDomainRepository.existsByBotIdAndDomain(botId, domain)) {
            throw new DuplicateResourceException("%s is already allowed".formatted(domain));
        }

        AllowedDomain saved = allowedDomainRepository.save(new AllowedDomain(bot, domain));
        log.info("allowed domain {} for bot {}", domain, botId);
        return AllowedDomainResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AllowedDomainResponse> listDomains(AuthenticatedUser caller, UUID botId) {
        requireBot(caller, botId);
        return allowedDomainRepository
                .findAllByBotIdAndOrganizationId(botId, caller.organizationId())
                .stream()
                .map(AllowedDomainResponse::from)
                .toList();
    }

    /**
     * Removes a domain.
     *
     * <p>Removing the last one silently disables the widget, since access fails
     * closed. That is the safe direction, and the dashboard should say so.
     */
    @Transactional
    public void removeDomain(AuthenticatedUser caller, UUID domainId) {
        requireRole(caller, MemberRole.ADMIN);

        AllowedDomain domain =
                allowedDomainRepository
                        .findByIdAndOrganizationId(domainId, caller.organizationId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Domain", domainId));

        allowedDomainRepository.delete(domain);
        log.info("removed allowed domain {}", domainId);
    }

    // ----------------------------------------------------------- internals --

    private Bot requireBot(AuthenticatedUser caller, UUID botId) {
        return botRepository
                .findByIdAndOrganizationId(botId, caller.organizationId())
                .orElseThrow(() -> ResourceNotFoundException.of("Bot", botId));
    }

    private static void requireRole(AuthenticatedUser caller, MemberRole required) {
        if (!caller.hasAtLeast(required)) {
            throw AccessDeniedException.insufficientRole();
        }
    }
}
