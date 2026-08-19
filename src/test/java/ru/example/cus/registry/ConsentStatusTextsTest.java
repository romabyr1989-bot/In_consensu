package ru.example.cus.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.example.cus.common.domain.ConsentStatus;
import ru.example.cus.registry.domain.ConsentStatusTexts;

/** FR-5.4: тексты статусов на русском, включая согласование числительного. */
class ConsentStatusTextsTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void simple_statuses_have_fixed_texts() {
        assertThat(ConsentStatusTexts.textOf(ConsentStatus.ACTIVE, null, NOW, MOSCOW))
                .isEqualTo("действует");
        assertThat(ConsentStatusTexts.textOf(ConsentStatus.EXPIRED, null, NOW, MOSCOW))
                .isEqualTo("истекло");
        assertThat(ConsentStatusTexts.textOf(ConsentStatus.REVOKED, null, NOW, MOSCOW))
                .isEqualTo("отозвано");
        assertThat(ConsentStatusTexts.textOf(ConsentStatus.SUPERSEDED, null, NOW, MOSCOW))
                .isEqualTo("заменено новым");
    }

    @Test
    void expiring_text_names_the_number_of_days() {
        Instant in15Days = NOW.plus(15, ChronoUnit.DAYS);

        assertThat(ConsentStatusTexts.textOf(ConsentStatus.EXPIRING, in15Days, NOW, MOSCOW))
                .isEqualTo("заканчивается через 15 дней");
    }

    @Test
    void last_day_is_worded_separately() {
        assertThat(ConsentStatusTexts.textOf(ConsentStatus.EXPIRING, NOW.plusSeconds(3600), NOW, MOSCOW))
                .isEqualTo("заканчивается сегодня");
    }

    @ParameterizedTest
    @CsvSource({
        "1, день",
        "2, дня",
        "3, дня",
        "4, дня",
        "5, дней",
        "11, дней",
        "12, дней",
        "14, дней",
        "15, дней",
        "21, день",
        "22, дня",
        "25, дней",
        "101, день",
        "111, дней",
        "112, дней"
    })
    void number_of_days_agrees_grammatically(long days, String expected) {
        assertThat(ConsentStatusTexts.pluralDays(days)).isEqualTo(expected);
    }
}
