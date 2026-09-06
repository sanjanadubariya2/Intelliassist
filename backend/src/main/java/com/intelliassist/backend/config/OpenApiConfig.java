package com.intelliassist.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation.
 *
 * <p>Generated from the controllers and DTOs rather than written separately: a
 * hand-maintained spec drifts from the code the first time somebody is in a
 * hurry, and a wrong spec is worse than none.
 *
 * <p>Two security schemes are declared because the API genuinely has two, and
 * conflating them in the documentation would invite exactly the mistake the
 * code is built to prevent — a dashboard token in a widget, or a widget key in
 * the dashboard.
 */
@Configuration
public class OpenApiConfig {

    private static final String DESCRIPTION =
            """
            Backend for IntelliAssist. Owns tenancy, authentication, bots, \
            documents and conversations; delegates retrieval and generation to a \
            private Python AI service that is never reachable from a browser.

            **Two separate credentials.**

            * `bearerAuth` — a dashboard JWT, for everything under `/api/v1` \
            except the public namespace. Obtained from `/api/v1/auth/login`.
            * `botApiKey` — an `X-Bot-Key` header, for `/api/v1/public/**` only. \
            Issued per bot, and additionally checked against the bot's \
            registered domains, since the key itself sits in a public web page.

            Neither credential works on the other's routes.

            **Errors** all share one shape, with a `requestId` that matches the \
            `X-Request-ID` response header and the server logs.
            """;

    @Bean
    public OpenAPI intelliAssistOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("IntelliAssist Backend API")
                                .version("0.1.0")
                                .description(DESCRIPTION))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Dashboard token from /api/v1/auth/login."))
                                .addSecuritySchemes(
                                        "botApiKey",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-Bot-Key")
                                                .description(
                                                        "Widget key. Only valid on /api/v1/public/**, "
                                                                + "and only from a registered domain.")));
    }
}
