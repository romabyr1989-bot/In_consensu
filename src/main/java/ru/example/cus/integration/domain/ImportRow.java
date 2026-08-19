package ru.example.cus.integration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import ru.example.cus.common.domain.ConsentSource;

/**
 * Одна строка файла импорта (FR-4.5).
 *
 * <p>Разбор отделён от записи в базу: строка сначала превращается в значения и список ошибок, и только
 * безошибочные строки доходят до регистрации. Так пробный запуск (dry-run) видит те же ошибки, что и боевой.
 */
public record ImportRow(
        int lineNumber,
        String externalId,
        String lastName,
        String firstName,
        String middleName,
        String phone,
        String email,
        String consentTypeCode,
        String formCode,
        Integer formVersion,
        Instant grantedAt,
        Instant validUntil,
        ConsentSource source,
        String sourceRef,
        String thirdPartyInn,
        List<String> pdnCategories,
        String documentRef,
        String note,
        List<Violation> violations) {

    /** Ошибка в конкретном поле строки: номер строки, поле, причина — как требует отчёт FR-4.5. */
    public record Violation(String field, String message) {}

    public static final List<String> REQUIRED_COLUMNS =
            List.of("external_id", "last_name", "first_name", "consent_type_code", "granted_at", "source");

    public boolean valid() {
        return violations.isEmpty();
    }

    /** Ключ идемпотентности строки (FR-4.5): source + source_ref + тип согласия + внешний идентификатор. */
    public String idempotencyKey() {
        return "import:" + source + ":" + (sourceRef == null ? "" : sourceRef) + ":" + consentTypeCode + ":"
                + externalId;
    }

    public static ImportRow from(int lineNumber, Map<String, String> cells, ZoneId operatorZone) {
        List<Violation> violations = new ArrayList<>();

        String externalId = required(cells, "external_id", violations);
        String lastName = required(cells, "last_name", violations);
        String firstName = required(cells, "first_name", violations);
        String consentTypeCode = required(cells, "consent_type_code", violations);

        ConsentSource source = null;
        String sourceValue = value(cells, "source");
        if (sourceValue.isBlank()) {
            violations.add(new Violation("source", "Не указан источник согласия"));
        } else {
            try {
                source = ConsentSource.valueOf(sourceValue.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                violations.add(new Violation("source", "Неизвестный источник: " + sourceValue));
            }
        }

        Instant grantedAt = instant(cells, "granted_at", operatorZone, violations, true);
        Instant validUntil = instant(cells, "valid_until", operatorZone, violations, false);
        if (grantedAt != null && validUntil != null && validUntil.isBefore(grantedAt)) {
            violations.add(new Violation("valid_until", "Срок действия заканчивается раньше даты согласия"));
        }

        Integer formVersion = null;
        String versionValue = value(cells, "form_version");
        if (!versionValue.isBlank()) {
            try {
                formVersion = Integer.valueOf(versionValue.trim());
            } catch (NumberFormatException e) {
                violations.add(new Violation("form_version", "Версия формы должна быть числом"));
            }
        }

        List<String> categories = Arrays.stream(value(cells, "pdn_categories").split("[,;|]"))
                .map(String::trim)
                .filter(category -> !category.isEmpty())
                .map(category -> category.toUpperCase(java.util.Locale.ROOT))
                .toList();

        return new ImportRow(
                lineNumber,
                externalId,
                lastName,
                firstName,
                emptyToNull(value(cells, "middle_name")),
                emptyToNull(value(cells, "phone")),
                emptyToNull(value(cells, "email")),
                consentTypeCode,
                emptyToNull(value(cells, "form_code")),
                formVersion,
                grantedAt,
                validUntil,
                source,
                emptyToNull(value(cells, "source_ref")),
                emptyToNull(value(cells, "third_party_inn")),
                categories,
                emptyToNull(value(cells, "document_ref")),
                emptyToNull(value(cells, "note")),
                violations);
    }

    private static String value(Map<String, String> cells, String column) {
        String raw = cells.get(column);
        return raw == null ? "" : raw.trim();
    }

    private static String required(Map<String, String> cells, String column, List<Violation> violations) {
        String raw = value(cells, column);
        if (raw.isBlank()) {
            violations.add(new Violation(column, "Обязательная колонка не заполнена"));
            return null;
        }
        return raw;
    }

    /**
     * Дата принимается и как момент с зоной, и как «дд.мм.гггг» или «гггг-мм-дд».
     *
     * <p>Историческая выгрузка почти никогда не содержит времени: дата без времени трактуется как начало дня
     * в таймзоне оператора (§8.7), иначе согласие «сдвинулось» бы на сутки.
     */
    private static Instant instant(
            Map<String, String> cells, String column, ZoneId zone, List<Violation> violations, boolean mandatory) {
        String raw = value(cells, column);
        if (raw.isBlank()) {
            if (mandatory) {
                violations.add(new Violation(column, "Обязательная колонка не заполнена"));
            }
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ignored) {
            // не момент с зоной — пробуем даты
        }
        for (java.time.format.DateTimeFormatter format : List.of(
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) {
            try {
                return LocalDate.parse(raw, format).atStartOfDay(zone).toInstant();
            } catch (Exception ignored) {
                // пробуем следующий формат
            }
        }
        violations.add(new Violation(column, "Дата должна быть в формате ISO-8601, гггг-мм-дд или дд.мм.гггг"));
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
