package com.intelliassist.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs the outcome and duration of each request.
 *
 * <p>Method, path, status and elapsed time only. Never headers, never the body:
 * headers carry the {@code Authorization} bearer token and the AI service API
 * key, and bodies carry passwords on the auth routes and customer document
 * content on upload.
 *
 * <p>Health probes are excluded because they fire every few seconds and would
 * bury everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            int status = response.getStatus();
            String message = "{} {} -> {} in {}ms";
            Object[] args = {request.getMethod(), request.getRequestURI(), status, millis};

            // A 5xx is our fault and deserves attention; a 4xx is the caller's
            // and is normal traffic.
            if (status >= 500) {
                log.error(message, args);
            } else if (status >= 400) {
                log.warn(message, args);
            } else {
                log.info(message, args);
            }
        }
    }
}
