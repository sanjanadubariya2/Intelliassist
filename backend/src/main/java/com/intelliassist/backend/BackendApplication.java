package com.intelliassist.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * IntelliAssist application backend.
 *
 * <p>This service owns the business domain: organizations, users, bots,
 * conversations and document metadata. It owns none of the retrieval pipeline —
 * extraction, embeddings, vector search, reranking and generation all belong to
 * the Python AI service, which this backend calls over HTTP and never
 * duplicates.
 *
 * <p>The split is deliberate. The two halves have genuinely different shapes:
 * the AI service is CPU- and model-bound and scales with corpus size, while this
 * one is I/O-bound and scales with tenants. Keeping them separate lets each be
 * deployed, scaled and reasoned about on its own terms.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
