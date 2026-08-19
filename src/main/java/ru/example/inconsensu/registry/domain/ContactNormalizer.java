package ru.example.inconsensu.registry.domain;

import java.util.Locale;
import ru.example.inconsensu.common.domain.ContactType;

/**
 * Приводит контакт к каноническому виду для поиска (§6: E.164 для телефона, lowercase для email).
 *
 * <p>Поиск по телефону обязан находить клиента независимо от того, как номер записали: «8 (916) 000-00-00»,
 * «+7 916 0000000» и «79160000000» — один и тот же человек (FR-5.2).
 */
public final class ContactNormalizer {

    private static final int RUSSIAN_NUMBER_LENGTH = 10;

    private ContactNormalizer() {}

    public static String normalize(ContactType type, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Контакт не может быть пустым");
        }
        return switch (type) {
            case PHONE -> normalizePhone(value);
            case EMAIL -> value.trim().toLowerCase(Locale.ROOT);
            case POSTAL_ADDRESS -> value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        };
    }

    /** Российские номера приводятся к +7XXXXXXXXXX; остальные — к «плюс и цифры». */
    public static String normalizePhone(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == RUSSIAN_NUMBER_LENGTH) {
            return "+7" + digits;
        }
        if (digits.length() == RUSSIAN_NUMBER_LENGTH + 1 && (digits.startsWith("8") || digits.startsWith("7"))) {
            return "+7" + digits.substring(1);
        }
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("Номер телефона должен содержать цифры");
        }
        return "+" + digits;
    }
}
