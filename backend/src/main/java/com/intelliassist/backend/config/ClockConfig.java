package com.intelliassist.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock.
 *
 * <p>Injected rather than called statically so a test can fix "today" and
 * assert on date bucketing without waiting for midnight. UTC, so a usage report
 * does not shift depending on which timezone the server happens to run in.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
