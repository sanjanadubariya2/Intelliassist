package com.intelliassist.backend.integration.ai;

import com.intelliassist.backend.config.AiServiceProperties;
import com.intelliassist.backend.config.CorrelationIdFilter;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The {@link WebClient} used for every call to the AI service.
 *
 * <p>Two things are attached here rather than at call sites, because a call site
 * can forget them:
 *
 * <ul>
 *   <li>the {@code X-API-Key} header, so an unauthenticated call is impossible
 *       to write;
 *   <li>the correlation id, so one request id spans both services and their
 *       logs can be joined.
 * </ul>
 */
@Configuration
public class AiWebClientConfig {

    static final String API_KEY_HEADER = "X-API-Key";

    /** Uploads are 50 MB; the default 256 KB buffer would reject the response. */
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    @Bean("aiWebClient")
    public WebClient aiWebClient(AiServiceProperties properties) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) properties.connectTimeout().toMillis())
                        // Connection-level idle guard, reset on every read. It
                        // is the outer bound only: per-call budgets are applied
                        // by the client, which needs finer control than one
                        // socket setting can express.
                        //
                        // Sized from the *largest* per-call budget, plus a
                        // margin. Anything shorter would fire before a stream's
                        // own first-event timeout and turn a slow retrieval
                        // into a connection reset instead of a clean timeout.
                        .doOnConnected(
                                connection ->
                                        connection.addHandlerLast(
                                                new ReadTimeoutHandler(
                                                        idleGuardSeconds(properties),
                                                        TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .filter(propagateCorrelationId())
                .build();
    }

    /** The longest a call may legitimately wait for bytes, plus a margin. */
    private static long idleGuardSeconds(AiServiceProperties properties) {
        long longest =
                Math.max(
                        properties.queryTimeout().toSeconds(),
                        properties.streamFirstEventTimeout().toSeconds());
        return longest + 15;
    }

    /**
     * Forwards this request's correlation id to the AI service.
     *
     * <p>The AI service honours an inbound {@code X-Request-ID} and echoes it,
     * so a single id follows a document upload from the dashboard through
     * ingestion and into the worker's logs.
     */
    private static ExchangeFilterFunction propagateCorrelationId() {
        return ExchangeFilterFunction.ofRequestProcessor(
                request -> {
                    String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (requestId == null) {
                        return reactor.core.publisher.Mono.just(request);
                    }
                    return reactor.core.publisher.Mono.just(
                            ClientRequest.from(request)
                                    .header(CorrelationIdFilter.HEADER, requestId)
                                    .build());
                });
    }
}
