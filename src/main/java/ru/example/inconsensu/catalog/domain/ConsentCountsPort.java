package ru.example.inconsensu.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.example.inconsensu.common.domain.ConsentStatus;

/**
 * Счётчики согласий для статистики каталога (§9 `/catalog/stats`, UI-2, UI-6).
 *
 * <p>Согласия принадлежат модулю registry, поэтому порт объявлен на стороне потребителя: прямое
 * обращение к чужому репозиторию запрещено §5.
 */
public interface ConsentCountsPort {

    /** Счётчик согласий одной группы (тип согласия или третье лицо) в одном статусе (FR-3.4). */
    record StatusCount(UUID groupId, ConsentStatus status, long count) {}

    /** Счётчик согласий одной группы без разбивки по статусам (FR-3.4). */
    record GroupCount(UUID groupId, long count) {}

    long activeConsents();

    long expiringConsents(Instant from, Instant to);

    long revokedConsentsSince(Instant since);

    /** Разрез по типам согласий: все статусы разом, чтобы не ходить в базу за каждым типом (FR-3.4). */
    List<StatusCount> countsByType();

    /** Разрез по третьим лицам; согласия без третьего лица в разрез не входят (FR-3.4). */
    List<StatusCount> countsByThirdParty();

    List<GroupCount> expiringByType(Instant from, Instant to);

    List<GroupCount> expiringByThirdParty(Instant from, Instant to);
}
