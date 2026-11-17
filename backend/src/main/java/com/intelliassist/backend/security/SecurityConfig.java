package com.intelliassist.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.controller.PublicChatController;
import com.intelliassist.backend.config.CorrelationIdFilter;
import com.intelliassist.backend.config.CorsProperties;
import com.intelliassist.backend.exception.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.MDC;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security wiring.
 *
 * <p>Stateless: there is no session, so the token is the whole of the request's
 * identity. That is what lets the backend scale horizontally without sticky
 * sessions or a shared session store.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PublicBotAuthenticationFilter publicBotAuthenticationFilter;
    private final CorsProperties corsProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsProperties corsProperties,
            ObjectMapper objectMapper,
            PublicBotAuthenticationFilter publicBotAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsProperties = corsProperties;
        this.objectMapper = objectMapper;
        this.publicBotAuthenticationFilter = publicBotAuthenticationFilter;
    }

    /**
     * BCrypt at strength 12.
     *
     * <p>Deliberately slow: the cost is what makes an offline attack against a
     * stolen table expensive. 12 is roughly a quarter-second per hash on
     * current hardware — unnoticeable on a login, punishing at scale.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * A manager that authenticates nothing.
     *
     * <p>Spring Security's provider chain is deliberately unused: credentials
     * are verified in {@code AuthService}, which then issues a JWT, and every
     * subsequent request is authenticated by {@link JwtAuthenticationFilter}.
     *
     * <p>Declaring this bean also stops Spring Boot auto-configuring an
     * in-memory user with a password printed to the console at startup — an
     * account nothing here would accept, and a confusing thing to leave in the
     * logs of an application that has real users.
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException(
                    "Password authentication is handled by AuthService, not by the provider chain");
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies are used, so there is no CSRF vector: an attacker's
                // page cannot make a browser attach a bearer token.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // A streamed response runs on an ASYNC
                                        // dispatch, and an error page on an
                                        // ERROR dispatch. Spring Security 6
                                        // authorises every dispatch type by
                                        // default, so without this the
                                        // continuation of an already-authorised
                                        // request is denied and the response is
                                        // truncated mid-stream.
                                        //
                                        // Safe because these are not new
                                        // requests: the original REQUEST
                                        // dispatch was fully authorised before
                                        // the controller ever returned.
                                        .dispatcherTypeMatchers(
                                                DispatcherType.ASYNC, DispatcherType.ERROR)
                                        .permitAll()
                                        // Registration and login must be
                                        // reachable without a token.
                                        .requestMatchers(
                                                "/api/v1/auth/register", "/api/v1/auth/login")
                                        .permitAll()
                                        // Single sign-on. The browser arrives
                                        // here before it has any token; the
                                        // signed state and nonce cookie are
                                        // what protect the round-trip.
                                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/oauth/**")
                                        .permitAll()
                                        // Probes carry no credentials.
                                        .requestMatchers("/api/v1/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/info")
                                        .permitAll()
                                        // API documentation. Disabled entirely
                                        // in production by configuration - a
                                        // public catalogue of every endpoint
                                        // and DTO is a gift to someone probing.
                                        .requestMatchers(
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        // Browsers send a credential-free
                                        // preflight before the real request.
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        // The widget script itself. It is public
                                        // by definition - it is served into every
                                        // customer's page - and carries no
                                        // credential of its own; the bot key is
                                        // supplied by the embedding page.
                                        .requestMatchers(HttpMethod.GET, "/widget/**")
                                        .permitAll()
                                        // Widget routes need the bot API key,
                                        // never a dashboard JWT. Requiring the
                                        // authority - rather than permitting
                                        // the path - means an unauthenticated
                                        // request is rejected by the chain
                                        // rather than reaching a controller
                                        // that would have to check for itself.
                                        .requestMatchers("/api/v1/public/**")
                                        .hasAuthority(PublicBotAuthentication.AUTHORITY)
                                        // Everything else needs a valid token.
                                        // Default-deny: a new endpoint is
                                        // protected unless someone opts it out.
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(this::writeUnauthorized)
                                        .accessDeniedHandler(this::writeForbidden))
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // After the JWT filter: it skips public paths, and this one
                // skips everything else, so the two never both run on a
                // request and neither credential works on the other's routes.
                .addFilterBefore(
                        publicBotAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS for the dashboard.
     *
     * <p>An explicit origin list, never {@code *}. The dashboard sends an
     * {@code Authorization} header, and a wildcard origin with credentials is
     * both rejected by browsers and unsafe.
     *
     * <p>Public widget endpoints will need their own, looser configuration
     * scoped to registered customer domains. That arrives with those endpoints.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        config.setExposedHeaders(List.of("X-Request-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        // Widget traffic comes from customer websites we cannot enumerate in
        // advance, so any origin may *ask*. That is safe only because this
        // configuration allows no credentials: no cookie is attached, and the
        // bot API key is an explicit header the page must set deliberately. A
        // hostile site can therefore make a request but gains nothing it could
        // not have made from a server anyway.
        //
        // The real origin check is server-side, against the bot's registered
        // domains, and CORS is a browser convenience rather than the boundary.
        CorsConfiguration publicConfig = new CorsConfiguration();
        publicConfig.setAllowedOriginPatterns(List.of("*"));
        // GET is needed for /public/config, which the widget fetches at load.
        // Without it the browser's request is rejected as an invalid CORS
        // request and never reaches authorization -- a 403 that looks like a
        // permissions bug and is not one.
        publicConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        publicConfig.setAllowedHeaders(
                List.of("Content-Type", "X-Bot-Key", "X-Request-ID"));
        publicConfig.setAllowCredentials(false);
        // A browser can only read headers named here. The widget needs the
        // conversation id from a streamed reply to keep the thread together.
        publicConfig.setExposedHeaders(
                List.of(PublicChatController.CONVERSATION_ID_HEADER, "X-Request-ID"));
        publicConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Registered first: the source returns the first pattern that matches,
        // so the narrower public rule must precede the general one.
        source.registerCorsConfiguration("/api/v1/public/**", publicConfig);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** 401 in the standard error shape, rather than Spring's default HTML. */
    private void writeUnauthorized(
            jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception)
            throws java.io.IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication required");
    }

    private void writeForbidden(
            jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception)
            throws java.io.IOException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    private void write(
            jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiError.of(
                        status,
                        code,
                        message,
                        request.getRequestURI(),
                        MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
