package ru.example.cus.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Выборка согласий для ежедневной задачи уведомлений (FR-9.1).
 *
 * <p>Порог «ровно N дней» считает владелец данных: у него есть и таймзона оператора, и материализованный
 * статус. Модуль уведомлений получает готовый список и занимается только адресами и шаблонами.
 */
public interface ExpiringConsentPort {

    /** Согласия, у которых до окончания срока осталось ровно {@code daysBefore} дней (FR-9.1). */
    List<ExpiringConsent> findExpiringIn(int daysBefore, UUID consentTypeId);

    /** Согласия, срок которых истёк за интервал; интервал полуоткрытый: [from, to). */
    List<ExpiringConsent> findExpiredBetween(Instant from, Instant to, UUID consentTypeId);

    /**
     * @param thirdPartyName заполнено только для согласий на передачу третьему лицу (FR-9.2)
     */
    record ExpiringConsent(
            UUID consentId,
            UUID subjectId,
            String subjectExternalId,
            String subjectFullName,
            UUID consentTypeId,
            String consentTypeName,
            String thirdPartyName,
            Instant validUntil) {}
}
