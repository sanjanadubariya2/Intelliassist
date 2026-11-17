package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.auth.AuthResponse;
import com.intelliassist.backend.dto.auth.CurrentUserResponse;
import com.intelliassist.backend.dto.auth.LoginRequest;
import com.intelliassist.backend.dto.auth.OrganizationResponse;
import com.intelliassist.backend.dto.auth.RegisterRequest;
import com.intelliassist.backend.dto.auth.UserResponse;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.entity.UserIdentity;
import com.intelliassist.backend.exception.AuthenticationFailedException;
import com.intelliassist.backend.exception.DuplicateResourceException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserIdentityRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.security.JwtService;
import com.intelliassist.backend.security.oauth.OAuthException;
import com.intelliassist.backend.security.oauth.OAuthProfile;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login, and answering "who am I". */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserIdentityRepository identityRepository;

    public AuthService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            UserIdentityRepository identityRepository) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.identityRepository = identityRepository;
    }

    /**
     * Creates a user, their organization and an OWNER membership.
     *
     * <p>All three in one transaction: a user with no organization could not use
     * the product, and an organization with no owner could never be
     * administered. Either both exist or neither does.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            // Safe to be specific here, unlike on login: the caller is
            // demonstrably trying to create this account, not probing for it.
            throw new DuplicateResourceException("An account with that email already exists");
        }

        User user =
                userRepository.save(
                        new User(
                                request.name().strip(),
                                email,
                                passwordEncoder.encode(request.password())));

        Organization organization = createOrganization(request.organizationName().strip());

        OrganizationMember membership =
                memberRepository.save(
                        new OrganizationMember(organization, user, MemberRole.OWNER));

        log.info("registered user {} and organization {}", user.getId(), organization.getId());

        return buildAuthResponse(user, organization, membership.getRole());
    }

    /**
     * Signs in someone a provider (Google, GitHub, LinkedIn) has vouched for.
     *
     * <p>Three cases, in this order:
     *
     * <ol>
     *   <li>The provider account is already linked: that user signs in.
     *   <li>It is not linked, but a user already has the same address. If that
     *       user also came from a provider (no password), the address was
     *       verified when they did, so this provider account is linked to
     *       them. If they have a password, it is refused: password sign-up
     *       does not verify addresses, so whoever registered first may not own
     *       it, and linking would hand them the real owner's sign-ins.
     *   <li>Nobody has the address: a new user is created with no password,
     *       along with a workspace of their own that they own.
     * </ol>
     */
    @Transactional
    public AuthResponse signInWithProvider(OAuthProfile profile) {
        var linked =
                identityRepository.findByProviderAndSubject(profile.provider(), profile.subject());
        if (linked.isPresent()) {
            User user = linked.get().getUser();
            log.info("sso login via {} for user {}", profile.provider(), user.getId());
            return signInExisting(user);
        }

        if (profile.email() == null || !profile.emailVerified()) {
            throw new OAuthException(
                    "no_verified_email", "The provider did not share a verified email address");
        }

        String email = User.normalizeEmail(profile.email());
        var existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getPasswordHash() != null) {
                throw new OAuthException(
                        "account_exists",
                        "An account with this email already exists. Sign in with your password.");
            }
            link(user, profile);
            log.info("linked {} to existing user {}", profile.provider(), user.getId());
            return signInExisting(user);
        }

        String name = displayName(profile.name(), email);
        User user = userRepository.save(new User(name, email, null));
        Organization organization = createOrganization(workspaceName(name));
        OrganizationMember membership =
                memberRepository.save(
                        new OrganizationMember(organization, user, MemberRole.OWNER));
        link(user, profile);

        log.info(
                "registered user {} via {} with organization {}",
                user.getId(),
                profile.provider(),
                organization.getId());
        return buildAuthResponse(user, organization, membership.getRole());
    }

    private void link(User user, OAuthProfile profile) {
        identityRepository.save(
                new UserIdentity(user, profile.provider(), profile.subject(), profile.email()));
    }

    private AuthResponse signInExisting(User user) {
        List<OrganizationMember> memberships = memberRepository.findAllByUserId(user.getId());
        if (memberships.isEmpty()) {
            throw new OAuthException("no_organization", "Account has no organization");
        }
        // Oldest membership is the default, exactly as for password login.
        OrganizationMember active = memberships.get(0);
        return buildAuthResponse(user, active.getOrganization(), active.getRole());
    }

    /**
     * Creates an organization with a unique slug.
     *
     * <p>Two sign-ups racing on the same name can both find the slug free and
     * one loses. The unique constraint is what actually guarantees
     * correctness; the pre-check is only an optimisation.
     */
    private Organization createOrganization(String name) {
        String slug = SlugGenerator.generate(name, organizationRepository::existsBySlug);
        try {
            return organizationRepository.saveAndFlush(new Organization(name, slug));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateResourceException(
                    "That organization name was just taken. Please try again.");
        }
    }

    /** The provider's name, else the part of the address before the {@code @}. */
    static String displayName(String providerName, String email) {
        String name = providerName != null ? providerName.strip() : "";
        if (name.isEmpty()) {
            name = email.substring(0, email.indexOf('@'));
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** "Ada's workspace", from the first word of the name. */
    static String workspaceName(String name) {
        String first = name.strip().split("\\s+")[0];
        String workspace = first + "'s workspace";
        return workspace.length() > 200 ? workspace.substring(0, 200) : workspace;
    }

    /**
     * Exchanges credentials for a token.
     *
     * <p>Note the shape: the password is verified even when the email is
     * unknown, and both failures produce the same exception. Returning early on
     * an unknown email would make the endpoint measurably faster for
     * non-existent accounts, which is enough to enumerate users by timing.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());
        var maybeUser = userRepository.findByEmail(email);

        if (maybeUser.isEmpty()) {
            // Hash against a dummy value so the work done is comparable.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            log.warn("login attempt for unknown account");
            throw AuthenticationFailedException.invalidCredentials();
        }

        User user = maybeUser.get();
        if (user.getPasswordHash() == null) {
            // Created through a provider and never given a password. Same cost
            // and same answer as a wrong password, for the same reason.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            log.warn("password login for provider-only user {}", user.getId());
            throw AuthenticationFailedException.invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("failed login for user {}", user.getId());
            throw AuthenticationFailedException.invalidCredentials();
        }

        List<OrganizationMember> memberships = memberRepository.findAllByUserId(user.getId());
        if (memberships.isEmpty()) {
            // Only reachable if every organization they belonged to was
            // deleted. There is nothing they could do with a token.
            log.warn("user {} has no organization membership", user.getId());
            throw new AuthenticationFailedException("Account has no organization");
        }

        // Oldest membership is the default; a switcher can re-issue for another.
        OrganizationMember active = memberships.get(0);
        log.info("login for user {} in organization {}", user.getId(),
                active.getOrganization().getId());

        return buildAuthResponse(user, active.getOrganization(), active.getRole());
    }

    /** Everything the dashboard needs about the caller. */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(AuthenticatedUser principal) {
        User user =
                userRepository
                        .findById(principal.userId())
                        .orElseThrow(() -> ResourceNotFoundException.of("User", principal.userId()));

        List<OrganizationMember> memberships = memberRepository.findAllByUserId(user.getId());

        OrganizationMember active =
                memberships.stream()
                        .filter(m -> m.getOrganization().getId().equals(principal.organizationId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        ResourceNotFoundException.of(
                                                "Organization", principal.organizationId()));

        List<CurrentUserResponse.MembershipSummary> all =
                memberships.stream()
                        .map(
                                m ->
                                        new CurrentUserResponse.MembershipSummary(
                                                OrganizationResponse.from(m.getOrganization()),
                                                m.getRole()))
                        .toList();

        return new CurrentUserResponse(
                UserResponse.from(user),
                OrganizationResponse.from(active.getOrganization()),
                active.getRole(),
                all);
    }

    private AuthResponse buildAuthResponse(User user, Organization organization, MemberRole role) {
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), organization.getId());
        return new AuthResponse(
                issued.token(),
                issued.expiresAt(),
                UserResponse.from(user),
                OrganizationResponse.from(organization),
                role);
    }

    /**
     * A real BCrypt hash of a value nobody knows.
     *
     * <p>Its only purpose is to give the unknown-email path the same cost as a
     * real verification, so response time does not reveal whether an account
     * exists.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.NR8N.PxvBu2PUFxLZjfPy8XHRnPMPZC";
}
