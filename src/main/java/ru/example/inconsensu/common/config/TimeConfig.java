package ru.example.inconsensu.common.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single source of "now" so that time dependent rules (§7.5, §7.9) stay testable. */
@Configuration
public class TimeConfig {

    /** PostgreSQL {@code timestamptz} resolution: an instant finer than this cannot survive a round trip. */
    static final Duration DATABASE_RESOLUTION = Duration.ofNanos(1_000);

    @Bean
    public Clock clock(InConsensuProperties properties) {
        return databasePrecision(Clock.system(properties.timezone()));
    }

    /**
     * Rounds a clock down to the resolution the database can store.
     *
     * <p>{@code Clock.system} reports nanoseconds on Linux while {@code timestamptz} keeps microseconds, so an
     * instant changed the moment it made a round trip. The audit chain of FR-10.1 hashes {@code occurred_at}: the
     * digest recomputed on read never matched the stored one, and every verification answered BROKEN on the
     * deployment platform (NFR-4) while passing on macOS, whose system clock is already microsecond precise.
     */
    static Clock databasePrecision(Clock base) {
        return Clock.tick(base, DATABASE_RESOLUTION);
    }
}
