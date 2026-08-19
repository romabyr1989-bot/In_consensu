package ru.example.inconsensu.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Разрешение часов приложения (FR-10.1, NFR-4).
 *
 * <p>Защита от дефекта, который виден только на Linux: там системные часы наносекундные, а `timestamptz`
 * хранит микросекунды, поэтому мгновение менялось при обращении к базе и цепочка аудита рапортовала
 * BROKEN. Часы проверяются на заведомо наносекундной основе, иначе на macOS тест прошёл бы и без
 * исправления — там системные часы и так микросекундные.
 */
class TimeConfigTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    @Test
    void nanoseconds_are_dropped_because_timestamptz_cannot_keep_them() {
        Instant withNanos = Instant.parse("2026-08-19T10:15:30Z").plusNanos(123_456_789);
        Clock clock = TimeConfig.databasePrecision(Clock.fixed(withNanos, ZONE));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-19T10:15:30.123456Z"));
    }

    @Test
    void an_instant_survives_the_round_trip_through_the_database_unchanged() {
        Instant withNanos = Instant.parse("2026-08-19T10:15:30Z").plusNanos(999_999_999);
        Instant now = TimeConfig.databasePrecision(Clock.fixed(withNanos, ZONE)).instant();

        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void the_bean_keeps_the_operator_timezone() {
        Clock clock = new TimeConfig().clock(new InConsensuProperties(ZONE, null, null, null, null));

        assertThat(clock.getZone()).isEqualTo(ZONE);
        assertThat(clock.instant().getNano() % 1_000).isZero();
    }
}
