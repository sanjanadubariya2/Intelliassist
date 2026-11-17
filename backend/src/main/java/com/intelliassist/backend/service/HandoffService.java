package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.chat.ConversationResponse;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.dto.handoff.InboxSummaryResponse;
import com.intelliassist.backend.dto.handoff.InboxView;
import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.exception.InvalidRequestException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human handoff inbox: conversations the assistant could not close out on
 * its own, who is handling each one, and how long they take.
 *
 * <p>Every read is organization-wide. A support team works one queue, and the
 * per-bot conversation list is the wrong shape for it.
 */
@Service
public class HandoffService {

    private static final int RESOLUTION_WINDOW_DAYS = 30;

    private final ConversationRepository conversationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public HandoffService(
            ConversationRepository conversationRepository,
            OrganizationMemberRepository memberRepository,
            UserRepository userRepository,
            Clock clock) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> list(
            AuthenticatedUser caller, InboxView view, int page, int size) {

        UUID organizationId = caller.organizationId();
        Pageable pageable = PageRequest.of(page, size);

        Page<Conversation> result =
                switch (view) {
                    case OPEN -> conversationRepository.findInboxByResolution(
                            organizationId, ConversationResolution.ESCALATED, pageable);
                    case MINE -> conversationRepository.findInboxAssignedTo(
                            organizationId, caller.userId(), pageable);
                    case UNASSIGNED -> conversationRepository.findInboxUnassigned(
                            organizationId, pageable);
                    case CLOSED -> conversationRepository.findInboxClosedOut(
                            organizationId, pageable);
                };

        return PageResponse.from(result, ConversationResponse::from);
    }

    @Transactional(readOnly = true)
    public InboxSummaryResponse summary(AuthenticatedUser caller) {
        UUID organizationId = caller.organizationId();
        Pageable one = PageRequest.of(0, 1);

        long open =
                conversationRepository
                        .findInboxByResolution(
                                organizationId, ConversationResolution.ESCALATED, one)
                        .getTotalElements();
        long mine =
                conversationRepository
                        .findInboxAssignedTo(organizationId, caller.userId(), one)
                        .getTotalElements();
        long unassigned =
                conversationRepository.findInboxUnassigned(organizationId, one).getTotalElements();

        Instant since = clock.instant().minus(Duration.ofDays(RESOLUTION_WINDOW_DAYS));
        List<Object[]> durations =
                conversationRepository.findHandoffDurationsSince(organizationId, since);

        return new InboxSummaryResponse(
                open, mine, unassigned, durations.size(), medianMinutes(durations));
    }

    /**
     * Assigns a conversation, or returns it to the queue.
     *
     * <p>Assigning a conversation that was never escalated escalates it: putting
     * a person on it is a handoff, and it should then appear in the inbox
     * rather than vanish into an unfiltered list.
     */
    @Transactional
    public ConversationResponse assign(
            AuthenticatedUser caller, UUID conversationId, UUID assigneeId) {

        Conversation conversation =
                conversationRepository
                        .findByIdAndOrganizationId(conversationId, caller.organizationId())
                        .orElseThrow(
                                () -> ResourceNotFoundException.of("Conversation", conversationId));

        if (assigneeId == null) {
            conversation.assignTo(null);
            return ConversationResponse.from(conversation);
        }

        // The assignee must be one of ours. Without this a caller could hand a
        // conversation, and so a visitor's words, to a stranger's account.
        if (!memberRepository.existsByUserIdAndOrganizationId(
                assigneeId, caller.organizationId())) {
            throw new InvalidRequestException("That person is not a member of this organization");
        }
        User assignee =
                userRepository
                        .findById(assigneeId)
                        .orElseThrow(() -> ResourceNotFoundException.of("User", assigneeId));

        conversation.assignTo(assignee);
        if (conversation.getResolution() == ConversationResolution.UNRESOLVED) {
            conversation.resolveAs(ConversationResolution.ESCALATED, clock.instant());
        }
        return ConversationResponse.from(conversation);
    }

    /**
     * Median minutes from escalation to resolution.
     *
     * <p>Median rather than mean: one conversation left over a weekend should
     * not set the number for the whole team.
     */
    static Long medianMinutes(List<Object[]> durations) {
        List<Long> minutes = new ArrayList<>(durations.size());
        for (Object[] row : durations) {
            Instant escalated = (Instant) row[0];
            Instant resolved = (Instant) row[1];
            if (escalated == null || resolved == null || resolved.isBefore(escalated)) {
                continue;
            }
            minutes.add(Duration.between(escalated, resolved).toMinutes());
        }
        if (minutes.isEmpty()) {
            return null;
        }
        Collections.sort(minutes);
        int middle = minutes.size() / 2;
        return minutes.size() % 2 == 1
                ? minutes.get(middle)
                : (minutes.get(middle - 1) + minutes.get(middle)) / 2;
    }
}
