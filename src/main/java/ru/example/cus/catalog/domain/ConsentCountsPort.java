package ru.example.cus.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Счётчики согласий для статистики каталога (§9 `/catalog/stats`, UI-2, UI-6).
 *
 * <p>Согласия принадлежат модулю registry, поэтому порт объявлен на стороне потребителя: прямое
 * обращение к чужому репозиторию запрещено §5.
 */
public interface ConsentCountsPort {

    long activeConsents();

    long expiringConsents(Instant from, Instant to);

    long revokedConsentsSince(Instant since);

    long activeConsentsOfType(UUID consentTypeId);

    long revokedConsentsOfType(UUID consentTypeId);
}
