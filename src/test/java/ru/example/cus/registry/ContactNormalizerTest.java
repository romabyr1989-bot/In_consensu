package ru.example.cus.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.registry.domain.ContactNormalizer;

/** FR-5.2: один и тот же человек должен находиться независимо от того, как записали его номер. */
class ContactNormalizerTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+7 916 000-00-41",
                "8 (916) 000-00-41",
                "79160000041",
                "9160000041",
                "+7-916-000-00-41",
                "  8 916 0000041  "
            })
    void every_way_of_writing_a_russian_number_normalizes_to_one_value(String written) {
        assertThat(ContactNormalizer.normalize(ContactType.PHONE, written)).isEqualTo("+79160000041");
    }

    @Test
    void foreign_numbers_keep_their_country_code() {
        assertThat(ContactNormalizer.normalize(ContactType.PHONE, "+49 30 123456789"))
                .isEqualTo("+4930123456789");
    }

    @Test
    void email_is_trimmed_and_lowercased() {
        assertThat(ContactNormalizer.normalize(ContactType.EMAIL, "  Travin.I@Example.RU "))
                .isEqualTo("travin.i@example.ru");
    }

    @Test
    void postal_address_collapses_whitespace() {
        assertThat(ContactNormalizer.normalize(ContactType.POSTAL_ADDRESS, " Москва,   ул. Ленина,  1 "))
                .isEqualTo("москва, ул. ленина, 1");
    }

    @Test
    void empty_and_digitless_values_are_rejected() {
        assertThatThrownBy(() -> ContactNormalizer.normalize(ContactType.PHONE, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactNormalizer.normalize(ContactType.PHONE, "телефон"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
