package ru.example.cus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.integration.domain.ImportRow;

/** FR-4.5: строка файла превращается в значения и список ошибок до всякой записи в базу. */
class ImportRowTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static Map<String, String> complete() {
        return Map.of(
                "external_id", "CRM-1002345",
                "last_name", "Травин",
                "first_name", "Иван",
                "middle_name", "Сергеевич",
                "phone", "+7 916 000-00-41",
                "consent_type_code", "PDN_PROCESSING",
                "granted_at", "12.03.2025",
                "source", "CONTRACT",
                "source_ref", "Д-2025/4471",
                "pdn_categories", "FIO; PHONE|EMAIL");
    }

    @Test
    void complete_row_is_parsed_without_violations() {
        ImportRow row = ImportRow.from(2, complete(), MOSCOW);

        assertThat(row.valid()).isTrue();
        assertThat(row.externalId()).isEqualTo("CRM-1002345");
        assertThat(row.source()).isEqualTo(ConsentSource.CONTRACT);
        assertThat(row.pdnCategories()).containsExactly("FIO", "PHONE", "EMAIL");
    }

    @Test
    void date_without_time_is_the_start_of_the_day_in_the_operator_timezone() {
        ImportRow row = ImportRow.from(2, complete(), MOSCOW);

        // 12.03.2025 00:00 по Москве — это 11.03.2025 21:00 UTC.
        assertThat(row.grantedAt()).isEqualTo(Instant.parse("2025-03-11T21:00:00Z"));
    }

    @Test
    void iso_date_and_full_instant_are_both_accepted() {
        var iso = new java.util.HashMap<>(complete());
        iso.put("granted_at", "2025-03-12");
        var instant = new java.util.HashMap<>(complete());
        instant.put("granted_at", "2025-03-12T09:41:00+03:00");

        assertThat(ImportRow.from(2, iso, MOSCOW).valid()).isTrue();
        assertThat(ImportRow.from(2, instant, MOSCOW).grantedAt()).isEqualTo(Instant.parse("2025-03-12T06:41:00Z"));
    }

    @Test
    void missing_mandatory_columns_are_reported_all_at_once() {
        ImportRow row = ImportRow.from(7, Map.of("external_id", "CRM-1"), MOSCOW);

        assertThat(row.valid()).isFalse();
        assertThat(row.violations())
                .extracting(ImportRow.Violation::field)
                .contains("last_name", "first_name", "consent_type_code", "granted_at", "source");
        assertThat(row.lineNumber()).isEqualTo(7);
    }

    @Test
    void unknown_source_and_malformed_date_are_reported_with_the_field_name() {
        var broken = new java.util.HashMap<>(complete());
        broken.put("source", "ИЗ_АРХИВА");
        broken.put("granted_at", "вчера");

        ImportRow row = ImportRow.from(3, broken, MOSCOW);

        assertThat(row.violations()).extracting(ImportRow.Violation::field).contains("source", "granted_at");
    }

    @Test
    void end_of_validity_before_the_consent_itself_is_rejected() {
        var inverted = new java.util.HashMap<>(complete());
        inverted.put("valid_until", "01.01.2020");

        assertThat(ImportRow.from(4, inverted, MOSCOW).violations())
                .extracting(ImportRow.Violation::field)
                .contains("valid_until");
    }

    @Test
    void idempotency_key_combines_source_reference_type_and_subject() {
        ImportRow row = ImportRow.from(2, complete(), MOSCOW);

        assertThat(row.idempotencyKey()).isEqualTo("import:CONTRACT:Д-2025/4471:PDN_PROCESSING:CRM-1002345");
    }
}
