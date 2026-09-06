package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.usage.BotUsageResponse;
import com.intelliassist.backend.dto.usage.UsageDayResponse;
import com.intelliassist.backend.entity.UsageRecord;
import com.intelliassist.backend.exception.InvalidRequestException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.UsageRecordRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import com.intelliassist.backend.repository.MessageRepository;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reports bot usage.
 *
 * <p>Recording is deliberately best-effort. Analytics must never be the reason
 * a customer's answer fails: if a counter cannot be written, the exception is
 * logged and swallowed. Losing a number from a chart is a minor inconvenience;
 * losing the answer is not.
 *
 * <p>Increments run in their own transaction ({@code REQUIRES_NEW}) so a
 * counter failure cannot roll back the exchange that was just persisted.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** A year keeps a range query bounded without limiting anything real. */
    private static final int MAX_RANGE_DAYS = 366;

    private final UsageStore store;
    private final MessageRepository messageRepository;
    private final UsageRecordRepository usageRepository;
    private final BotRepository botRepository;
    private final Clock clock;

    public UsageService(
            UsageStore store,
            UsageRecordRepository usageRepository,
            BotRepository botRepository,
            MessageRepository messageRepository,
            Clock clock) {
        this.store = store;
        this.usageRepository = usageRepository;
        this.botRepository = botRepository;
        this.messageRepository = messageRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------ recording --

    public void recordConversationStarted(UUID botId) {
        applyQuietly(botId, 1, 0, 0, 0);
    }

    /**
     * Records one answered exchange.
     *
     * @param grounded false when retrieval found nothing, which is the number
     *     that reveals a gap in the corpus
     * @param tokens zero for streamed answers, which do not report usage
     */
    public void recordExchange(UUID botId, boolean grounded, int tokens) {
        applyQuietly(botId, 0, 2, grounded ? 0 : 1, tokens);
    }

    /**
     * Applies increments, swallowing any failure.
     *
     * <p>Best-effort by design: analytics must never be the reason a customer
     * loses an answer they already paid a model call for. The work is delegated
     * to {@link UsageStore} rather than done here, because a
     * {@code @Transactional} method called through {@code this} is not
     * transactional at all — and this method's own catch block would hide that.
     */
    private void applyQuietly(
            UUID botId, int conversations, int messages, int ungrounded, long tokens) {
        try {
            store.apply(botId, LocalDate.now(clock), conversations, messages, ungrounded, tokens);
        } catch (RuntimeException exception) {
            log.warn("could not record usage for bot {}: {}", botId, exception.toString());
        }
    }

    // ------------------------------------------------------------ reporting --

    @Transactional(readOnly = true)
    public BotUsageResponse getBotUsage(
            AuthenticatedUser caller, UUID botId, LocalDate from, LocalDate to) {

        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(29);
        validateRange(start, end);

        // Confirms the bot is the caller's before returning anything about it.
        if (botRepository.findByIdAndOrganizationId(botId, caller.organizationId()).isEmpty()) {
            throw ResourceNotFoundException.of("Bot", botId);
        }

        List<UsageRecord> records =
                usageRepository.findRange(botId, caller.organizationId(), start, end);

        List<UsageDayResponse> days = records.stream().map(UsageDayResponse::from).toList();

        return new BotUsageResponse(
                botId,
                start,
                end,
                days,
                days.stream().mapToInt(UsageDayResponse::conversations).sum(),
                days.stream().mapToInt(UsageDayResponse::messages).sum(),
                days.stream().mapToInt(UsageDayResponse::answers).sum(),
                days.stream().mapToInt(UsageDayResponse::groundedAnswers).sum(),
                days.stream().mapToInt(UsageDayResponse::ungroundedAnswers).sum(),
                days.stream().mapToLong(UsageDayResponse::tokens).sum(),
                // Averaged in the database. Loading every message to average a
                // column in Java would drag whole conversation histories across
                // the wire to produce one number, and would slow down for ever.
                messageRepository.averageLatencyMs(
                        botId,
                        caller.organizationId(),
                        start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    /**
     * Days with no activity are simply absent.
     *
     * <p>Filling gaps with zero rows would triple the payload of a quiet
     * month's report to say nothing, and a chart library can interpolate a
     * missing day far more cheaply than we can transmit it.
     */
    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new InvalidRequestException(
                    "Range must be at most %d days".formatted(MAX_RANGE_DAYS));
        }
    }
}
