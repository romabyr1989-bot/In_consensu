package ru.example.inconsensu.registry.domain;

import ru.example.inconsensu.common.domain.ContactType;

/**
 * Маскирование контактов для ролей без права на ПДн (FR-5.1, NFR-3, UI-0.10).
 *
 * <p>Формат совпадает с Приложением A: «+7 9** ***-**-41», «t***@example.ru». Видимого остатка достаточно, чтобы
 * сотрудник сверил номер, названный клиентом, и недостаточно, чтобы выгрузить базу.
 */
public final class ContactMasker {

    private static final String MASKED_PHONE_TEMPLATE = "+7 9** ***-**-";

    private ContactMasker() {}

    public static String mask(ContactType type, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return switch (type) {
            case PHONE -> maskPhone(value);
            case EMAIL -> maskEmail(value);
            case POSTAL_ADDRESS -> "адрес скрыт";
        };
    }

    private static String maskPhone(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 2) {
            return "***";
        }
        return MASKED_PHONE_TEMPLATE + digits.substring(digits.length() - 2);
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
