package ru.example.cus.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.registry.domain.ContactMasker;

/** FR-5.1, NFR-3: маскирование должно совпадать с Приложением A и не раскрывать номер целиком. */
class ContactMaskerTest {

    @Test
    void phone_is_masked_as_in_appendix_a() {
        assertThat(ContactMasker.mask(ContactType.PHONE, "+79160000041")).isEqualTo("+7 9** ***-**-41");
    }

    @Test
    void email_keeps_only_the_first_letter_and_the_domain() {
        assertThat(ContactMasker.mask(ContactType.EMAIL, "travin@example.ru")).isEqualTo("t***@example.ru");
    }

    @Test
    void postal_address_is_hidden_entirely() {
        assertThat(ContactMasker.mask(ContactType.POSTAL_ADDRESS, "Москва, ул. Ленина, 1"))
                .isEqualTo("адрес скрыт");
    }

    @Test
    void masked_value_never_contains_the_middle_of_the_number() {
        String masked = ContactMasker.mask(ContactType.PHONE, "+79161234541");

        assertThat(masked).doesNotContain("1234").endsWith("41");
    }

    @Test
    void malformed_values_do_not_leak() {
        assertThat(ContactMasker.mask(ContactType.PHONE, "x")).isEqualTo("***");
        assertThat(ContactMasker.mask(ContactType.EMAIL, "no-at-sign")).isEqualTo("***");
        assertThat(ContactMasker.mask(ContactType.EMAIL, null)).isNull();
    }
}
