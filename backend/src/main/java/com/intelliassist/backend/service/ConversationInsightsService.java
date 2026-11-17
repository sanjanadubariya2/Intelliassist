package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.insights.ConversationInsightsResponse;
import com.intelliassist.backend.dto.insights.QuestionClusterResponse;
import com.intelliassist.backend.dto.insights.ResolutionDayResponse;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.Message;
import com.intelliassist.backend.entity.MessageRole;
import com.intelliassist.backend.exception.InvalidRequestException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.MessageRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation intelligence: the questions visitors ask, the ones the bot could
 * not answer, and how conversations turn out.
 *
 * <p>Computed on read from the transcript tables. Questions are grouped by a
 * normalised form (case, punctuation and spacing removed) rather than by
 * meaning. That is deliberately modest: it never merges two different
 * questions, and it needs no model call, so it is fast, free and cannot be
 * wrong in a way nobody can explain.
 */
@Service
public class ConversationInsightsService {

    /** The most recent messages analysed per request, so cost stays bounded. */
    static final int MESSAGE_LIMIT = 5_000;

    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int TOP = 10;

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final BotRepository botRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final Clock clock;

    public ConversationInsightsService(
            BotRepository botRepository,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            Clock clock) {
        this.botRepository = botRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConversationInsightsResponse insights(
            AuthenticatedUser caller, UUID botId, LocalDate from, LocalDate to) {

        UUID organizationId = caller.organizationId();
        requireBot(botId, organizationId);

        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new InvalidRequestException(
                    "Range must be at most %d days".formatted(MAX_RANGE_DAYS));
        }

        Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Message> messages =
                messageRepository.findRecentForBot(
                        botId,
                        organizationId,
                        fromInstant,
                        toInstant,
                        PageRequest.of(0, MESSAGE_LIMIT));
        List<Question> questions = pairQuestions(messages);

        int unanswered = (int) questions.stream().filter(q -> !q.answered()).count();
        int answered = questions.size() - unanswered;

        List<QuestionClusterResponse> clusters = cluster(questions);
        List<QuestionClusterResponse> top = clusters.stream().limit(TOP).toList();
        List<QuestionClusterResponse> gaps =
                clusters.stream()
                        .filter(cluster -> cluster.unansweredCount() > 0)
                        .sorted(
                                Comparator.comparingInt(QuestionClusterResponse::unansweredCount)
                                        .reversed()
                                        .thenComparing(
                                                QuestionClusterResponse::lastAskedAt,
                                                Comparator.reverseOrder()))
                        .limit(TOP)
                        .toList();

        List<Object[]> outcomes =
                conversationRepository.findOutcomesStartedBetween(
                        botId, organizationId, fromInstant, toInstant);
        Trend trend = trend(outcomes, start, end);

        int decided = trend.resolved + trend.escalated;
        return new ConversationInsightsResponse(
                start,
                end,
                questions.size(),
                answered,
                unanswered,
                questions.isEmpty() ? null : (double) answered / questions.size(),
                outcomes.size(),
                trend.resolved,
                trend.escalated,
                trend.open,
                decided == 0 ? null : (double) trend.resolved / decided,
                top,
                gaps,
                trend.days,
                messages.size() >= MESSAGE_LIMIT);
    }

    /**
     * The questions the bot most often failed to answer since {@code since}.
     *
     * <p>Shared with the knowledge base health check, where each one is a
     * suggested FAQ: a question visitors demonstrably ask and the documents
     * demonstrably do not cover.
     */
    @Transactional(readOnly = true)
    public List<QuestionClusterResponse> unansweredSince(
            UUID botId, UUID organizationId, Instant since, int limit) {

        List<Message> messages =
                messageRepository.findRecentForBot(
                        botId,
                        organizationId,
                        since,
                        clock.instant().plusSeconds(1),
                        PageRequest.of(0, MESSAGE_LIMIT));

        return cluster(pairQuestions(messages).stream().filter(q -> !q.answered()).toList())
                .stream()
                .limit(limit)
                .toList();
    }

    // -------------------------------------------------------------- analysis --

    /** A visitor's question and whether the answer that followed was grounded. */
    record Question(String text, Instant askedAt, boolean answered) {}

    /**
     * Pairs each user message with the assistant reply that followed it.
     *
     * <p>Messages arrive newest first across many conversations. They are
     * regrouped per conversation and walked oldest first; a question with no
     * reply yet (a stream still in flight, or one that failed) is counted as
     * unanswered, because the visitor did not get an answer.
     */
    static List<Question> pairQuestions(List<Message> messages) {
        Map<UUID, List<Message>> byConversation = new LinkedHashMap<>();
        for (Message message : messages) {
            byConversation
                    .computeIfAbsent(message.getConversation().getId(), id -> new ArrayList<>())
                    .add(message);
        }

        List<Question> questions = new ArrayList<>();
        for (List<Message> thread : byConversation.values()) {
            // Oldest first. Both turns of an exchange are written in one
            // transaction and can share a timestamp, so on a tie the question
            // sorts before its answer.
            thread.sort(
                    Comparator.comparing(Message::getCreatedAt)
                            .thenComparing(m -> m.getRole() == MessageRole.USER ? 0 : 1));

            Message pending = null;
            for (Message message : thread) {
                if (message.getRole() == MessageRole.USER) {
                    if (pending != null) {
                        questions.add(
                                new Question(pending.getContent(), pending.getCreatedAt(), false));
                    }
                    pending = message;
                } else if (message.getRole() == MessageRole.ASSISTANT && pending != null) {
                    boolean grounded = Boolean.TRUE.equals(message.getGrounded());
                    questions.add(
                            new Question(pending.getContent(), pending.getCreatedAt(), grounded));
                    pending = null;
                }
            }
            if (pending != null) {
                questions.add(new Question(pending.getContent(), pending.getCreatedAt(), false));
            }
        }
        return questions;
    }

    /** Groups questions by normalised text, most asked first. */
    static List<QuestionClusterResponse> cluster(List<Question> questions) {
        Map<String, Cluster> clusters = new HashMap<>();
        for (Question question : questions) {
            String key = normalise(question.text());
            if (key.isEmpty()) {
                continue;
            }
            clusters.computeIfAbsent(key, k -> new Cluster()).add(question);
        }

        return clusters.values().stream()
                .map(Cluster::toResponse)
                .sorted(
                        Comparator.comparingInt(QuestionClusterResponse::count)
                                .reversed()
                                .thenComparing(
                                        QuestionClusterResponse::lastAskedAt,
                                        Comparator.reverseOrder()))
                .toList();
    }

    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String words = NOT_WORD.matcher(lower).replaceAll(" ");
        return SPACES.matcher(words).replaceAll(" ").strip();
    }

    private static final class Cluster {
        private int count;
        private int unanswered;
        private Instant last;
        private String wording;

        void add(Question question) {
            count++;
            if (!question.answered()) {
                unanswered++;
            }
            // Show the latest wording: it is the one most likely to match how
            // visitors phrase it today.
            if (last == null || question.askedAt().isAfter(last)) {
                last = question.askedAt();
                wording = question.text().strip();
            }
        }

        QuestionClusterResponse toResponse() {
            String shown = wording.length() <= 200 ? wording : wording.substring(0, 197) + "...";
            return new QuestionClusterResponse(shown, count, unanswered, last);
        }
    }

    private static final class Trend {
        int resolved;
        int escalated;
        int open;
        List<ResolutionDayResponse> days;
    }

    /**
     * Daily outcomes for conversations started in the window.
     *
     * <p>Every day in the range is present, including empty ones, so a chart of
     * it shows a quiet Sunday as zero rather than skipping it.
     */
    private static Trend trend(List<Object[]> outcomes, LocalDate start, LocalDate end) {
        Map<LocalDate, int[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            byDay.put(day, new int[4]);
        }

        Trend trend = new Trend();
        for (Object[] row : outcomes) {
            Instant startedAt = (Instant) row[0];
            ConversationResolution resolution = (ConversationResolution) row[1];
            int[] counts = byDay.get(LocalDate.ofInstant(startedAt, ZoneOffset.UTC));
            if (counts == null) {
                continue;
            }
            counts[0]++;
            switch (resolution) {
                case RESOLVED -> {
                    counts[1]++;
                    trend.resolved++;
                }
                case ESCALATED -> {
                    counts[2]++;
                    trend.escalated++;
                }
                case UNRESOLVED -> {
                    counts[3]++;
                    trend.open++;
                }
            }
        }

        List<ResolutionDayResponse> days = new ArrayList<>(byDay.size());
        byDay.forEach(
                (date, c) -> days.add(new ResolutionDayResponse(date, c[0], c[1], c[2], c[3])));
        trend.days = days;
        return trend;
    }

    private void requireBot(UUID botId, UUID organizationId) {
        if (botRepository.findByIdAndOrganizationId(botId, organizationId).isEmpty()) {
            throw ResourceNotFoundException.of("Bot", botId);
        }
    }
}
