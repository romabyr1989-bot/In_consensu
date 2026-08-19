package ru.example.cus.registry.domain;

import java.time.Instant;
import java.time.ZoneId;
import ru.example.cus.common.domain.ConsentStatus;

/** Тексты статусов согласия на русском (FR-5.4, UI-0.7). */
public final class ConsentStatusTexts {

    private ConsentStatusTexts() {}

    public static String textOf(ConsentStatus status, Instant validUntil, Instant now, ZoneId operatorZone) {
        return switch (status) {
            case ACTIVE -> "действует";
            case EXPIRING -> expiringText(validUntil, now, operatorZone);
            case EXPIRED -> "истекло";
            case REVOKED -> "отозвано";
            case SUPERSEDED -> "заменено новым";
        };
    }

    private static String expiringText(Instant validUntil, Instant now, ZoneId operatorZone) {
        if (validUntil == null) {
            return "действует";
        }
        long days = ConsentStatusCalculator.daysLeft(validUntil, now, operatorZone);
        if (days <= 0) {
            return "заканчивается сегодня";
        }
        return "заканчивается через " + days + " " + pluralDays(days);
    }

    /** Русский язык требует согласования числительного: «через 1 день», «через 2 дня», «через 15 дней». */
    public static String pluralDays(long days) {
        long lastTwo = days % 100;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "дней";
        }
        return switch ((int) (days % 10)) {
            case 1 -> "день";
            case 2, 3, 4 -> "дня";
            default -> "дней";
        };
    }
}
