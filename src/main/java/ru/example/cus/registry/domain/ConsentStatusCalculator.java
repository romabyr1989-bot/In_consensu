package ru.example.cus.registry.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import ru.example.cus.common.domain.ConsentStatus;

/**
 * Правило расчёта статуса согласия (FR-5.3).
 *
 * <p>Источник правды — расчёт при чтении, а поле {@code consent.status} лишь материализуется для фильтров и
 * отчётов. Поэтому расчёт живёт здесь: и обработчик запроса, и ежедневная задача обязаны получать один и тот
 * же ответ, что закреплено тестом.
 *
 * <p>Порядок проверок важен: отозванное согласие остаётся отозванным, даже если его срок уже истёк, — иначе
 * карточка показала бы «истекло» там, где юридически значим именно отзыв.
 */
public final class ConsentStatusCalculator {

    private ConsentStatusCalculator() {}

    public static ConsentStatus statusOf(
            Instant revokedAt, boolean superseded, Instant validUntil, Instant now, int expiringDays) {
        if (revokedAt != null) {
            return ConsentStatus.REVOKED;
        }
        if (superseded) {
            return ConsentStatus.SUPERSEDED;
        }
        if (validUntil == null) {
            return ConsentStatus.ACTIVE;
        }
        if (validUntil.isBefore(now)) {
            return ConsentStatus.EXPIRED;
        }
        return validUntil.isAfter(now.plus(expiringDays, ChronoUnit.DAYS))
                ? ConsentStatus.ACTIVE
                : ConsentStatus.EXPIRING;
    }

    /**
     * Календарных дней до окончания в таймзоне оператора (§8.2).
     *
     * <p>Считается по датам, а не по разнице моментов: клиенту важно, что согласие кончается «послезавтра», а
     * не что до него 47 часов.
     */
    public static long daysLeft(Instant validUntil, Instant now, ZoneId operatorZone) {
        LocalDate today = LocalDate.ofInstant(now, operatorZone);
        LocalDate end = LocalDate.ofInstant(validUntil, operatorZone);
        return ChronoUnit.DAYS.between(today, end);
    }
}
