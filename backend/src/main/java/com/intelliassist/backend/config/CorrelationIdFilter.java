package com.intelliassist.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id that follows it across service boundaries.
 *
 * <p>The AI service honours and echoes the same {@code X-Request-ID} header, so
 * one id joins a log line here to the corresponding line there. That is what
 * makes a failure inside the RAG pipeline traceable from a dashboard complaint
 * — without it, correlating two services means guessing from timestamps.
 *
 * <p>An inbound id is preserved rather than replaced, so a trace that starts at
 * a gateway or in the frontend keeps its identity for the whole hop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    /** Bounded so a hostile caller cannot write an unbounded value into logs. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused; leaving the id behind would stamp
            // it onto an unrelated later request.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String inbound) {
        if (inbound == null || inbound.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String trimmed = inbound.strip();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        // Strip anything that could forge a log line or inject a header.
        return trimmed.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
