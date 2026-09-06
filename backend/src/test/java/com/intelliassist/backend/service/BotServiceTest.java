package com.intelliassist.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intelliassist.backend.dto.bot.CreateBotRequest;
import com.intelliassist.backend.dto.bot.UpdateBotRequest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.BotStatus;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.exception.AccessDeniedException;
import com.intelliassist.backend.exception.DuplicateResourceException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Authorization rules, tested without a database.
 *
 * <p>Mocks are the right tool here: the questions are "which role may do this"
 * and "is the organization always applied", and both are answered by what the
 * service asks the repository for.
 */
@ExtendWith(MockitoExtension.class)
class BotServiceTest {

    @Mock private BotRepository botRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private KnowledgeService knowledgeService;
    @InjectMocks private BotService botService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID botId = UUID.randomUUID();

    private AuthenticatedUser owner;
    private AuthenticatedUser admin;
    private AuthenticatedUser member;

    @BeforeEach
    void setUp() {
        owner = new AuthenticatedUser(UUID.randomUUID(), orgId, MemberRole.OWNER, "o@x.com");
        admin = new AuthenticatedUser(UUID.randomUUID(), orgId, MemberRole.ADMIN, "a@x.com");
        member = new AuthenticatedUser(UUID.randomUUID(), orgId, MemberRole.MEMBER, "m@x.com");
    }

    private CreateBotRequest createRequest() {
        return new CreateBotRequest("Support Bot", "Answers questions", "en", null);
    }

    private UpdateBotRequest updateRequest() {
        return new UpdateBotRequest("Renamed", "New description", BotStatus.INACTIVE, "en", null);
    }

    private Bot existingBot() {
        Organization organization = new Organization("Acme", "acme");
        return new Bot(organization, "Support Bot", "Answers questions", "en");
    }

    // ------------------------------------------------------------- roles ----

    @Test
    void memberCannotCreateABot() {
        assertThatThrownBy(() -> botService.create(member, createRequest()))
                .isInstanceOf(AccessDeniedException.class);

        // The check must happen before any write is attempted.
        verify(botRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCanCreateABot() {
        when(botRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Support Bot"))
                .thenReturn(false);
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(new Organization("Acme", "acme")));
        when(botRepository.saveAndFlush(any(Bot.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(botService.create(admin, createRequest()).name()).isEqualTo("Support Bot");
    }

    @Test
    void ownerCanCreateABot() {
        when(botRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Support Bot"))
                .thenReturn(false);
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(new Organization("Acme", "acme")));
        when(botRepository.saveAndFlush(any(Bot.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(botService.create(owner, createRequest())).isNotNull();
    }

    @Test
    void memberCannotUpdateABot() {
        assertThatThrownBy(() -> botService.update(member, botId, updateRequest()))
                .isInstanceOf(AccessDeniedException.class);

        verify(botRepository, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void memberCannotDeleteABot() {
        assertThatThrownBy(() -> botService.delete(member, botId))
                .isInstanceOf(AccessDeniedException.class);

        verify(botRepository, never()).deleteById(any());
    }

    @Test
    void memberCanReadABot() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId))
                .thenReturn(Optional.of(existingBot()));

        assertThat(botService.get(member, botId)).isNotNull();
    }

    // -------------------------------------------------------- isolation ----

    @Test
    void everyLookupIsScopedToTheCallersOrganization() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId))
                .thenReturn(Optional.of(existingBot()));

        botService.get(member, botId);

        // The organization comes from the principal, never from the request.
        verify(botRepository).findByIdAndOrganizationId(eq(botId), eq(orgId));
        verify(botRepository, never()).findById(any());
    }

    @Test
    void anotherOrganizationsBotLooksLikeItDoesNotExist() {
        // Returning 404 rather than 403 is deliberate: a 403 would confirm the
        // id exists somewhere, letting one tenant probe another's id space.
        when(botRepository.findByIdAndOrganizationId(botId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> botService.get(member, botId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletingAnotherOrganizationsBotIsNotFound() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> botService.delete(admin, botId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(botRepository, never()).deleteById(any());
        // Nothing is removed from the AI service for a bot that is not ours.
        verify(knowledgeService, never()).deleteKnowledgeBaseForBot(any(), any());
    }

    @Test
    void deletingABotAlsoRemovesItsAiKnowledgeBase() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId))
                .thenReturn(Optional.of(existingBot()));

        botService.delete(admin, botId);

        // Without this the AI service keeps the knowledge base, its chunks and
        // its embeddings forever, with no row left pointing at them.
        verify(knowledgeService).deleteKnowledgeBaseForBot(botId, orgId);
        verify(botRepository).deleteById(botId);
    }

    @Test
    void aFailedRemoteDeleteLeavesTheBotIntact() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId))
                .thenReturn(Optional.of(existingBot()));
        org.mockito.Mockito.doThrow(
                        com.intelliassist.backend.integration.ai.AiServiceException.unavailable(null))
                .when(knowledgeService)
                .deleteKnowledgeBaseForBot(botId, orgId);

        assertThatThrownBy(() -> botService.delete(admin, botId))
                .isInstanceOf(
                        com.intelliassist.backend.integration.ai.AiServiceException.class);

        // Remote first: deleting locally would strand the corpus with nothing
        // left to identify it. Keeping the bot makes the whole thing retryable.
        verify(botRepository, never()).deleteById(any());
    }

    // ------------------------------------------------------- name rules ----

    @Test
    void duplicateNameWithinTheOrganizationIsRejected() {
        when(botRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Support Bot"))
                .thenReturn(true);

        assertThatThrownBy(() -> botService.create(admin, createRequest()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void renamingABotToItsOwnNameIsAllowed() {
        Bot bot = existingBot();
        when(botRepository.findByIdAndOrganizationId(botId, orgId)).thenReturn(Optional.of(bot));

        var sameName =
                new UpdateBotRequest(
                        "Support Bot", "Updated", BotStatus.ACTIVE, "en", null);

        assertThat(botService.update(admin, botId, sameName).description()).isEqualTo("Updated");
        // A self-rename must not be treated as a collision.
        verify(botRepository, never()).existsByOrganizationIdAndNameIgnoreCase(any(), any());
    }

    @Test
    void renamingOntoAnExistingNameIsRejected() {
        when(botRepository.findByIdAndOrganizationId(botId, orgId))
                .thenReturn(Optional.of(existingBot()));
        when(botRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Renamed"))
                .thenReturn(true);

        assertThatThrownBy(() -> botService.update(admin, botId, updateRequest()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ---------------------------------------------------------- defaults ----

    @Test
    void blankOptionalFieldsAreStoredAsNullNotEmptyStrings() {
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(new Organization("Acme", "acme")));
        when(botRepository.saveAndFlush(any(Bot.class))).thenAnswer(call -> call.getArgument(0));

        var request = new CreateBotRequest("Bot", "   ", "en", "   ");
        var created = botService.create(admin, request);

        assertThat(created.description()).isNull();
        assertThat(created.systemPrompt()).isNull();
    }

    @Test
    void missingLanguageFallsBackToEnglish() {
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(new Organization("Acme", "acme")));
        when(botRepository.saveAndFlush(any(Bot.class))).thenAnswer(call -> call.getArgument(0));

        var request = new CreateBotRequest("Bot", null, null, null);

        assertThat(botService.create(admin, request).defaultLanguage()).isEqualTo("en");
    }

    @Test
    void newBotsStartActive() {
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(new Organization("Acme", "acme")));
        when(botRepository.saveAndFlush(any(Bot.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(botService.create(admin, createRequest()).status()).isEqualTo(BotStatus.ACTIVE);
    }
}
