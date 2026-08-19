package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.registry.domain.ConsentStatusCalculator;

/** FR-5.3: правило расчёта статуса, одинаковое для чтения и для ежедневной задачи. */
class ConsentStatusCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final int EXPIRING_DAYS = 30;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static ConsentStatus status(Instant revokedAt, boolean superseded, Instant validUntil) {
        return ConsentStatusCalculator.statusOf(revokedAt, superseded, validUntil, NOW, EXPIRING_DAYS);
    }

    @Test
    void consent_without_an_end_date_is_active() {
        assertThat(status(null, false, null)).isEqualTo(ConsentStatus.ACTIVE);
    }

    @Test
    void consent_far_from_its_end_is_active() {
        assertThat(status(null, false, NOW.plus(90, ChronoUnit.DAYS))).isEqualTo(ConsentStatus.ACTIVE);
    }

    @Test
    void consent_inside_the_threshold_is_expiring() {
        assertThat(status(null, false, NOW.plus(15, ChronoUnit.DAYS))).isEqualTo(ConsentStatus.EXPIRING);
        assertThat(status(null, false, NOW.plus(EXPIRING_DAYS, ChronoUnit.DAYS)))
                .isEqualTo(ConsentStatus.EXPIRING);
    }

    @Test
    void boundary_of_the_threshold_belongs_to_active() {
        assertThat(status(null, false, NOW.plus(EXPIRING_DAYS, ChronoUnit.DAYS).plusSeconds(1)))
                .isEqualTo(ConsentStatus.ACTIVE);
    }

    @Test
    void consent_past_its_end_is_expired() {
        assertThat(status(null, false, NOW.minusSeconds(1))).isEqualTo(ConsentStatus.EXPIRED);
    }

    @Test
    void revocation_wins_over_expiry_and_replacement() {
        // Юридически значим именно отзыв: карточка не должна показывать «истекло» там, где клиент отозвал.
        assertThat(status(NOW.minusSeconds(10), false, NOW.minus(5, ChronoUnit.DAYS)))
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(status(NOW.minusSeconds(10), true, null)).isEqualTo(ConsentStatus.REVOKED);
    }

    @Test
    void replacement_wins_over_expiry() {
        assertThat(status(null, true, NOW.minus(5, ChronoUnit.DAYS))).isEqualTo(ConsentStatus.SUPERSEDED);
        assertThat(status(null, true, null)).isEqualTo(ConsentStatus.SUPERSEDED);
    }

    @Test
    void days_left_is_counted_in_calendar_days_of_the_operator_timezone() {
        // 23:30 по Москве и 00:30 следующего дня — «завтра», а не «через 1 час».
        Instant lateEvening = Instant.parse("2026-08-18T20:30:00Z");
        Instant nextMorning = Instant.parse("2026-08-18T21:30:00Z");

        assertThat(ConsentStatusCalculator.daysLeft(nextMorning, lateEvening, MOSCOW))
                .isEqualTo(1);
    }

    @Test
    void days_left_is_zero_on_the_last_day() {
        Instant morning = Instant.parse("2026-08-18T06:00:00Z");
        Instant evening = Instant.parse("2026-08-18T18:00:00Z");

        assertThat(ConsentStatusCalculator.daysLeft(evening, morning, MOSCOW)).isZero();
    }
}
