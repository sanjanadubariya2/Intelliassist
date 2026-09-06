package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.bot.BotAppearanceRequest;
import com.intelliassist.backend.dto.bot.BotResponse;
import com.intelliassist.backend.dto.bot.CreateBotRequest;
import com.intelliassist.backend.dto.bot.UpdateBotRequest;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.exception.AccessDeniedException;
import com.intelliassist.backend.exception.DuplicateResourceException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bot management.
 *
 * <p>Two rules, applied to every method:
 *
 * <ol>
 *   <li><strong>Isolation.</strong> A bot is always looked up by id
 *       <em>and</em> the caller's organization. The organization comes from the
 *       authenticated principal, never from the request — a client-supplied
 *       tenant id is the classic broken-access-control bug.
 *   <li><strong>Role.</strong> Reads need MEMBER; writes need ADMIN. Checked
 *       explicitly rather than with annotations, so the rule is visible at the
 *       point it applies.
 * </ol>
 *
 * <p>A bot belonging to another organization returns {@code 404}, not
 * {@code 403}. Distinguishing them would confirm that an id exists somewhere,
 * which lets one tenant probe another's id space.
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final BotRepository botRepository;
    private final OrganizationRepository organizationRepository;
    private final KnowledgeService knowledgeService;

    public BotService(
            BotRepository botRepository,
            OrganizationRepository organizationRepository,
            KnowledgeService knowledgeService) {
        this.botRepository = botRepository;
        this.organizationRepository = organizationRepository;
        this.knowledgeService = knowledgeService;
    }

    @Transactional
    public BotResponse create(AuthenticatedUser caller, CreateBotRequest request) {
        requireRole(caller, MemberRole.ADMIN);

        String name = request.name().strip();
        if (botRepository.existsByOrganizationIdAndNameIgnoreCase(caller.organizationId(), name)) {
            throw new DuplicateResourceException("A bot named '%s' already exists".formatted(name));
        }

        Organization organization =
                organizationRepository
                        .findById(caller.organizationId())
                        .orElseThrow(
                                () ->
                                        ResourceNotFoundException.of(
                                                "Organization", caller.organizationId()));

        Bot bot =
                new Bot(
                        organization,
                        name,
                        strip(request.description()),
                        request.defaultLanguageOrFallback());
        bot.setSystemPrompt(strip(request.systemPrompt()));

        try {
            Bot saved = botRepository.saveAndFlush(bot);
            log.info(
                    "created bot {} in organization {}", saved.getId(), caller.organizationId());
            return BotResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            // Two concurrent creates with the same name: both passed the check,
            // one lost the unique constraint. The constraint is the real
            // guarantee; the check above is only a nicer error message.
            throw new DuplicateResourceException("A bot named '%s' already exists".formatted(name));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BotResponse> list(AuthenticatedUser caller, Pageable pageable) {
        return PageResponse.from(
                botRepository.findAllByOrganizationId(caller.organizationId(), pageable),
                BotResponse::from);
    }

    @Transactional(readOnly = true)
    public BotResponse get(AuthenticatedUser caller, UUID botId) {
        return BotResponse.from(requireBot(caller, botId));
    }

    @Transactional
    public BotResponse update(AuthenticatedUser caller, UUID botId, UpdateBotRequest request) {
        requireRole(caller, MemberRole.ADMIN);
        Bot bot = requireBot(caller, botId);

        String name = request.name().strip();
        // Only a genuine rename can collide; renaming a bot to its own name is
        // a no-op the uniqueness check must not reject.
        if (!bot.getName().equalsIgnoreCase(name)
                && botRepository.existsByOrganizationIdAndNameIgnoreCase(
                        caller.organizationId(), name)) {
            throw new DuplicateResourceException("A bot named '%s' already exists".formatted(name));
        }

        bot.setName(name);
        bot.setDescription(strip(request.description()));
        bot.setStatus(request.status());
        bot.setDefaultLanguage(request.defaultLanguageOrFallback());
        bot.setSystemPrompt(strip(request.systemPrompt()));

        log.info("updated bot {} in organization {}", botId, caller.organizationId());
        return BotResponse.from(bot);
    }

    /**
     * Deletes a bot and its corpus.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}: it makes an HTTP call
     * to the AI service, and holding a pooled database connection across a
     * network round trip is how connection pools get exhausted. The ownership
     * check and the delete are each their own short transaction instead.
     */
    public void delete(AuthenticatedUser caller, UUID botId) {
        requireRole(caller, MemberRole.ADMIN);
        // Confirms the bot is ours before anything is removed anywhere.
        requireBot(caller, botId);

        // Remote first, for the same reason document deletion is: our rows
        // cascade away here, but the AI service knows nothing about that. If we
        // deleted locally first and the remote call then failed, its knowledge
        // base would survive with nothing left pointing at it - storage paid
        // for forever, reachable by nothing.
        //
        // The remote delete is idempotent, so a failure here leaves the bot
        // intact and the whole operation safe to retry.
        knowledgeService.deleteKnowledgeBaseForBot(botId, caller.organizationId());

        // Documents, conversations and messages cascade from the bot row.
        // deleteById rather than delete(entity): the entity was loaded in a
        // different transaction and is detached by now.
        botRepository.deleteById(botId);
        log.info("deleted bot {} from organization {}", botId, caller.organizationId());
    }

    // ------------------------------------------------------------ internals --

    /**
     * Loads a bot the caller is entitled to see.
     *
     * <p>The single choke point for bot access. Everything above goes through
     * it, so isolation is one method to audit rather than a rule repeated in
     * five places.
     */
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

    private static String strip(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Applies a change from the Customization screen.
     *
     * <p>Separate from {@link #update}: that one renames and re-scopes a bot
     * and requires ADMIN, while appearance is cosmetic and every member who can
     * see the bot may change it. Folding them together would either over-
     * restrict a colour picker or under-restrict a rename.
     */
    @Transactional
    public BotResponse updateAppearance(
            AuthenticatedUser caller, UUID botId, BotAppearanceRequest request) {

        Bot bot = requireBot(caller, botId);

        bot.updateAppearance(
                request.primaryColor(),
                request.widgetPosition(),
                request.welcomeMessage(),
                request.fallbackMessage(),
                request.launcherIcon());

        log.info("updated appearance for bot {} in organization {}", botId, caller.organizationId());
        return BotResponse.from(bot);
    }
}
