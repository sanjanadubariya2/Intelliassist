package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.insights.QuestionClusterResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse.Issue;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse.IssueType;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse.Severity;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse.SuggestedFaq;
import com.intelliassist.backend.entity.Document;
import com.intelliassist.backend.entity.DocumentStatus;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.DocumentRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds what is wrong with a knowledge base before a visitor does.
 *
 * <p>Works from metadata the backend already holds: status, size, name and age
 * of each document, plus the questions the bot failed to answer. The documents'
 * text is never read here; it lives only in the AI service.
 */
@Service
public class KnowledgeHealthService {

    /** Older than this and a document is worth re-reading for accuracy. */
    static final Duration OUTDATED_AFTER = Duration.ofDays(180);

    /** Indexing normally finishes in minutes; this long means something broke. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);

    private static final int DOCUMENT_LIMIT = 1_000;
    private static final int FAQ_LOOKBACK_DAYS = 30;
    private static final int FAQ_LIMIT = 8;

    /** Version markers stripped to find "the same document" across uploads. */
    private static final Pattern VERSION_TOKENS =
            Pattern.compile(
                    "\\b(v\\d+(\\.\\d+)*|rev\\d*|ver\\d*|version\\d*|final|draft|old|new|latest|"
                            + "updated|copy|backup|\\d{4}[-_.]?\\d{0,2}[-_.]?\\d{0,2}|\\d+)\\b");

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    private final BotRepository botRepository;
    private final DocumentRepository documentRepository;
    private final ConversationInsightsService insightsService;
    private final Clock clock;

    public KnowledgeHealthService(
            BotRepository botRepository,
            DocumentRepository documentRepository,
            ConversationInsightsService insightsService,
            Clock clock) {
        this.botRepository = botRepository;
        this.documentRepository = documentRepository;
        this.insightsService = insightsService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public KnowledgeHealthResponse check(AuthenticatedUser caller, UUID botId) {
        UUID organizationId = caller.organizationId();
        if (botRepository.findByIdAndOrganizationId(botId, organizationId).isEmpty()) {
            throw ResourceNotFoundException.of("Bot", botId);
        }

        Instant now = clock.instant();
        List<Document> documents =
                documentRepository
                        .findAllByBotIdAndOrganizationId(
                                botId,
                                organizationId,
                                PageRequest.of(
                                        0, DOCUMENT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")))
                        .getContent();

        List<Issue> issues = findIssues(documents, now);

        List<SuggestedFaq> faqs =
                insightsService
                        .unansweredSince(
                                botId,
                                organizationId,
                                now.minus(Duration.ofDays(FAQ_LOOKBACK_DAYS)),
                                FAQ_LIMIT)
                        .stream()
                        .map(KnowledgeHealthService::toFaq)
                        .toList();

        int indexed =
                (int) documents.stream().filter(d -> d.getStatus() == DocumentStatus.INDEXED).count();
        int chunks =
                documents.stream()
                        .map(Document::getChunkCount)
                        .filter(count -> count != null)
                        .mapToInt(Integer::intValue)
                        .sum();

        return new KnowledgeHealthResponse(
                score(documents, issues), documents.size(), indexed, chunks, issues, faqs, now);
    }

    // ----------------------------------------------------------------- checks --

    static List<Issue> findIssues(List<Document> documents, Instant now) {
        List<Issue> issues = new ArrayList<>();

        if (documents.isEmpty()) {
            issues.add(
                    new Issue(
                            IssueType.EMPTY_KNOWLEDGE_BASE,
                            Severity.HIGH,
                            "No documents yet",
                            "This bot has nothing to answer from, so every question gets the"
                                    + " fallback message.",
                            "Upload your FAQ, policies or product docs to get started.",
                            List.of(),
                            List.of()));
            return issues;
        }

        for (Document document : documents) {
            String name = document.getOriginalFilename();
            switch (document.getStatus()) {
                case FAILED -> issues.add(
                        single(
                                IssueType.FAILED,
                                Severity.HIGH,
                                "Indexing failed",
                                document.getErrorMessage() == null
                                        ? "This document could not be read, so the bot cannot use it."
                                        : document.getErrorMessage(),
                                "Check the file opens correctly, then delete it and upload it again.",
                                document));
                case PENDING, EXTRACTING, CHUNKING, EMBEDDING -> {
                    if (document.getCreatedAt() != null
                            && Duration.between(document.getCreatedAt(), now).compareTo(STUCK_AFTER)
                                    > 0) {
                        issues.add(
                                single(
                                        IssueType.STUCK,
                                        Severity.MEDIUM,
                                        "Stuck in the indexing queue",
                                        "Queued for over %d minutes. Indexing normally takes a few."
                                                .formatted(STUCK_AFTER.toMinutes()),
                                        "Make sure the ingestion worker is running, or re-upload"
                                                + " the file.",
                                        document));
                    }
                }
                case INDEXED -> {
                    Integer chunks = document.getChunkCount();
                    if (chunks != null && chunks == 0) {
                        issues.add(
                                single(
                                        IssueType.NO_CONTENT,
                                        Severity.MEDIUM,
                                        "No readable text",
                                        "Indexed, but no text was extracted. Scanned PDFs and"
                                                + " images often look like this.",
                                        "Upload a text-based version, or export the text to"
                                                + " a .txt or .docx file.",
                                        document));
                    }
                }
            }

            Issue outdated = outdated(document, name, now);
            if (outdated != null) {
                issues.add(outdated);
            }
        }

        issues.addAll(duplicates(documents));
        issues.addAll(conflictingVersions(documents));

        issues.sort(Comparator.comparing(Issue::severity).thenComparing(Issue::type));
        return issues;
    }

    /**
     * Old documents, and documents whose name pins them to a past year.
     *
     * <p>A file called {@code pricing-2023.pdf} answering questions in 2026 is
     * the classic way a support bot ends up quoting last year's prices.
     */
    private static Issue outdated(Document document, String name, Instant now) {
        int thisYear = now.atZone(ZoneOffset.UTC).getYear();
        Matcher matcher = YEAR.matcher(name == null ? "" : name);
        int newest = -1;
        while (matcher.find()) {
            newest = Math.max(newest, Integer.parseInt(matcher.group()));
        }
        if (newest > 0 && newest < thisYear - 1) {
            return single(
                    IssueType.OUTDATED,
                    Severity.MEDIUM,
                    "Refers to %d".formatted(newest),
                    "The filename suggests this describes %d. Prices, policies and dates may"
                                    .formatted(newest)
                            + " have changed since.",
                    "Replace it with the current version, or delete it if it no longer applies.",
                    document);
        }

        if (document.getCreatedAt() != null
                && Duration.between(document.getCreatedAt(), now).compareTo(OUTDATED_AFTER) > 0) {
            long months = Duration.between(document.getCreatedAt(), now).toDays() / 30;
            return single(
                    IssueType.OUTDATED,
                    Severity.LOW,
                    "Not updated in %d months".formatted(months),
                    "Uploaded over six months ago and never replaced.",
                    "Review it for anything that has changed, and re-upload if it has.",
                    document);
        }
        return null;
    }

    /**
     * The same file uploaded more than once.
     *
     * <p>Identical size and type is a strong signal of an identical file, even
     * when it was renamed. Duplicates are not wrong, but they crowd out other
     * sources: retrieval returns the same passage twice instead of two
     * different ones.
     */
    private static List<Issue> duplicates(List<Document> documents) {
        Map<String, List<Document>> bySignature = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document.getStatus() == DocumentStatus.FAILED) {
                continue;
            }
            String signature = document.getSizeBytes() + "|" + document.getContentType();
            bySignature.computeIfAbsent(signature, key -> new ArrayList<>()).add(document);
        }

        List<Issue> issues = new ArrayList<>();
        for (List<Document> group : bySignature.values()) {
            if (group.size() < 2) {
                continue;
            }
            issues.add(
                    group(
                            IssueType.DUPLICATE,
                            Severity.LOW,
                            "Possible duplicate",
                            "%d files are the same size and type, so they are likely the same"
                                            .formatted(group.size())
                                    + " document uploaded twice.",
                            "Keep one and delete the rest, so answers draw on more varied"
                                    + " sources.",
                            group));
        }
        return issues;
    }

    /**
     * Several versions of what looks like the same document.
     *
     * <p>{@code refund-policy-v1.pdf} and {@code refund-policy-v2.pdf} are both
     * searched, and when they disagree the bot can quote either. This is the
     * single most common way a knowledge base contradicts itself.
     */
    private static List<Issue> conflictingVersions(List<Document> documents) {
        Map<String, List<Document>> byStem = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document.getStatus() == DocumentStatus.FAILED) {
                continue;
            }
            String stem = stem(document.getOriginalFilename());
            if (stem.length() < 3) {
                continue;
            }
            byStem.computeIfAbsent(stem, key -> new ArrayList<>()).add(document);
        }

        List<Issue> issues = new ArrayList<>();
        for (List<Document> group : byStem.values()) {
            // All the same size is a duplicate, which is reported on its own.
            boolean sizesDiffer =
                    group.stream().map(Document::getSizeBytes).distinct().count() > 1;
            if (group.size() < 2 || !sizesDiffer) {
                continue;
            }
            issues.add(
                    group(
                            IssueType.CONFLICTING_VERSIONS,
                            Severity.HIGH,
                            "Possibly conflicting versions",
                            "%d versions of what looks like the same document are all live."
                                            .formatted(group.size())
                                    + " If they disagree, the bot may quote the old one.",
                            "Keep the current version and delete the others.",
                            group));
        }
        return issues;
    }

    /** A filename reduced to what stays the same across versions of it. */
    static String stem(String filename) {
        if (filename == null) {
            return "";
        }
        String base = filename.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = NON_ALNUM.matcher(base).replaceAll(" ");
        base = VERSION_TOKENS.matcher(base).replaceAll(" ");
        return NON_ALNUM.matcher(base.strip()).replaceAll("-");
    }

    /**
     * 100 minus a penalty per finding, weighted by severity.
     *
     * <p>An empty knowledge base scores zero outright: there is nothing healthy
     * about a bot that can answer nothing.
     */
    static int score(List<Document> documents, List<Issue> issues) {
        if (documents.isEmpty()) {
            return 0;
        }
        int penalty = 0;
        for (Issue issue : issues) {
            penalty +=
                    switch (issue.severity()) {
                        case HIGH -> 20;
                        case MEDIUM -> 8;
                        case LOW -> 3;
                    };
        }
        return Math.max(0, 100 - penalty);
    }

    private static Issue single(
            IssueType type,
            Severity severity,
            String title,
            String detail,
            String recommendation,
            Document document) {
        return new Issue(
                type,
                severity,
                title,
                detail,
                recommendation,
                List.of(document.getId()),
                List.of(document.getOriginalFilename()));
    }

    private static Issue group(
            IssueType type,
            Severity severity,
            String title,
            String detail,
            String recommendation,
            List<Document> group) {
        return new Issue(
                type,
                severity,
                title,
                detail,
                recommendation,
                group.stream().map(Document::getId).toList(),
                group.stream().map(Document::getOriginalFilename).toList());
    }

    private static SuggestedFaq toFaq(QuestionClusterResponse cluster) {
        return new SuggestedFaq(cluster.question(), cluster.unansweredCount(), cluster.lastAskedAt());
    }
}
