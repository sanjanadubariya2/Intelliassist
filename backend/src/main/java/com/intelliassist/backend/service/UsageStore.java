package com.intelliassist.backend.service;

import com.intelliassist.backend.repository.UsageRecordRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of usage recording.
 *
 * <p>A separate bean for the same reason as {@link KnowledgeStore} and
 * {@link ChatStore}: Spring's {@code @Transactional} is applied by a proxy, and
 * a proxy cannot intercept a call made through {@code this}. Putting the
 * annotation on a method that its own class calls internally produces code that
 * looks transactional and is not — and here that failure was invisible, because
 * the caller deliberately swallows exceptions.
 *
 * <p>{@code REQUIRES_NEW} keeps counting isolated: a failure updating a counter
 * must never roll back the exchange that was just persisted.
 */
@Service
public class UsageStore {

    private final UsageRecordRepository usageRepository;

    public UsageStore(UsageRecordRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    /**
     * Creates today's bucket if needed, then applies the increments.
     *
     * <p>Safe under concurrency without locking: the insert cannot conflict
     * ({@code ON CONFLICT DO NOTHING}) and the update is column arithmetic
     * rather than a read-modify-write, so two simultaneous messages both count.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(
            UUID botId,
            LocalDate date,
            int conversations,
            int messages,
            int ungrounded,
            long tokens) {

        usageRepository.ensureBucket(botId, date);
        usageRepository.increment(botId, date, conversations, messages, ungrounded, tokens);
    }
}
