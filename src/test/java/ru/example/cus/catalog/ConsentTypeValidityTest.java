package ru.example.cus.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.example.cus.catalog.domain.ConsentType;

/** FR-4.3: срок действия типа задаётся ISO-8601 duration, пусто означает «до отзыва». */
class ConsentTypeValidityTest {

    @Test
    void period_and_duration_forms_are_accepted() {
        assertThat(ConsentType.normalizeValidity("P1Y")).isEqualTo("P1Y");
        assertThat(ConsentType.normalizeValidity("p180d")).isEqualTo("P180D");
        assertThat(ConsentType.normalizeValidity("PT720H")).isEqualTo("PT720H");
    }

    @Test
    void blank_means_until_revoked() {
        assertThat(ConsentType.normalizeValidity(null)).isNull();
        assertThat(ConsentType.normalizeValidity("   ")).isNull();
    }

    @Test
    void nonsense_is_rejected_with_a_readable_message() {
        assertThatThrownBy(() -> ConsentType.normalizeValidity("1 год"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }
}
