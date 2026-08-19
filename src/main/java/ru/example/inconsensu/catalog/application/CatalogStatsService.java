package ru.example.inconsensu.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.domain.ConsentCountsPort;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Статистика каталога (§9 `/catalog/stats`, UI-2, UI-6).
 *
 * <p>Согласия принадлежат модулю registry, который сам зависит от каталога, поэтому их счётчики приходят
 * через порт (§5). Третьи лица — наоборот, зависимость каталога, и обращение к их сервису прямое: лишний
 * порт здесь только замкнул бы модули в цикл.
 */
@Service
public class CatalogStatsService {

    /** Окно «скоро» и «недавно» для плиток дашборда: 30 дней, как в UI-2. */
    public static final Duration WINDOW = Duration.ofDays(30);

    /** @param awaitingApproval формы, ждущие решения согласующих (FR-2.1) */
    public record CatalogStats(
            long activeConsents,
            long expiringConsents,
            long revokedConsents,
            long awaitingApproval,
            long publishedForms,
            long expiringContracts,
            List<TypeStats> byType,
            List<ThirdPartyStats> byThirdParty) {}

    /**
     * Разрез по типу согласия (FR-3.4).
     *
     * @param active действующие: статусы ACTIVE и EXPIRING вместе, как на плитке дашборда
     * @param expiring подмножество действующих со статусом EXPIRING
     * @param expiringSoon действующие, срок которых заканчивается в ближайшие 30 дней
     */
    public record TypeStats(
            String code,
            String nameRu,
            long active,
            long expiring,
            long expired,
            long revoked,
            long superseded,
            long expiringSoon) {}

    /** Тот же разрез по третьему лицу; поля означают то же, что в {@link TypeStats} (FR-3.4). */
    public record ThirdPartyStats(
            UUID id,
            String inn,
            String name,
            long active,
            long expiring,
            long expired,
            long revoked,
            long superseded,
            long expiringSoon) {}

    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final ConsentCountsPort consentCounts;
    private final ThirdPartyService thirdParties;
    private final Clock clock;

    public CatalogStatsService(
            ConsentTypeService types,
            ConsentFormService forms,
            ConsentCountsPort consentCounts,
            ThirdPartyService thirdParties,
            Clock clock) {
        this.types = types;
        this.forms = forms;
        this.consentCounts = consentCounts;
        this.thirdParties = thirdParties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogStats stats() {
        Instant now = clock.instant();
        Instant horizon = now.plus(WINDOW);

        return new CatalogStats(
                consentCounts.activeConsents(),
                consentCounts.expiringConsents(now, horizon),
                consentCounts.revokedConsentsSince(now.minus(WINDOW)),
                forms.awaitingDecision().size(),
                forms.publishedCount(),
                thirdParties.contractsEndingWithin((int) WINDOW.toDays()).size(),
                byType(now, horizon),
                byThirdParty(now, horizon));
    }

    /** Разрез по типам на текущий момент (FR-3.4). */
    @Transactional(readOnly = true)
    public List<TypeStats> byType() {
        Instant now = clock.instant();
        return byType(now, now.plus(WINDOW));
    }

    /** Разрез по третьим лицам на текущий момент (FR-3.4). */
    @Transactional(readOnly = true)
    public List<ThirdPartyStats> byThirdParty() {
        Instant now = clock.instant();
        return byThirdParty(now, now.plus(WINDOW));
    }

    @Transactional(readOnly = true)
    public List<TypeStats> byType(Instant now, Instant horizon) {
        Map<UUID, Map<ConsentStatus, Long>> counts = group(consentCounts.countsByType());
        Map<UUID, Long> expiringSoon = flatten(consentCounts.expiringByType(now, horizon));
        return types.allTypes().stream()
                .map(type -> {
                    Map<ConsentStatus, Long> byStatus = counts.getOrDefault(type.getId(), Map.of());
                    return new TypeStats(
                            type.getCode(),
                            type.getNameRu(),
                            count(byStatus, ConsentStatus.ACTIVE) + count(byStatus, ConsentStatus.EXPIRING),
                            count(byStatus, ConsentStatus.EXPIRING),
                            count(byStatus, ConsentStatus.EXPIRED),
                            count(byStatus, ConsentStatus.REVOKED),
                            count(byStatus, ConsentStatus.SUPERSEDED),
                            expiringSoon.getOrDefault(type.getId(), 0L));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ThirdPartyStats> byThirdParty(Instant now, Instant horizon) {
        Map<UUID, Map<ConsentStatus, Long>> counts = group(consentCounts.countsByThirdParty());
        Map<UUID, Long> expiringSoon = flatten(consentCounts.expiringByThirdParty(now, horizon));
        return thirdParties.all().stream()
                .map(party -> {
                    Map<ConsentStatus, Long> byStatus = counts.getOrDefault(party.getId(), Map.of());
                    return new ThirdPartyStats(
                            party.getId(),
                            party.getInn(),
                            party.getName(),
                            count(byStatus, ConsentStatus.ACTIVE) + count(byStatus, ConsentStatus.EXPIRING),
                            count(byStatus, ConsentStatus.EXPIRING),
                            count(byStatus, ConsentStatus.EXPIRED),
                            count(byStatus, ConsentStatus.REVOKED),
                            count(byStatus, ConsentStatus.SUPERSEDED),
                            expiringSoon.getOrDefault(party.getId(), 0L));
                })
                .toList();
    }

    /** Все типы для экспорта каталога: он отдаёт те же счётчики построчно (FR-1.1, FR-3.3). */
    @Transactional(readOnly = true)
    public List<ConsentType> allTypes() {
        return types.allTypes();
    }

    /** Третьи лица для экспорта и разрезов статистики. */
    @Transactional(readOnly = true)
    public List<ThirdParty> allThirdParties() {
        return thirdParties.all();
    }

    private static Map<UUID, Map<ConsentStatus, Long>> group(List<ConsentCountsPort.StatusCount> rows) {
        Map<UUID, Map<ConsentStatus, Long>> grouped = new HashMap<>();
        for (ConsentCountsPort.StatusCount row : rows) {
            grouped.computeIfAbsent(row.groupId(), key -> new EnumMap<>(ConsentStatus.class))
                    .merge(row.status(), row.count(), Long::sum);
        }
        return grouped;
    }

    private static Map<UUID, Long> flatten(List<ConsentCountsPort.GroupCount> rows) {
        Map<UUID, Long> flattened = new HashMap<>();
        for (ConsentCountsPort.GroupCount row : rows) {
            flattened.merge(row.groupId(), row.count(), Long::sum);
        }
        return flattened;
    }

    private static long count(Map<ConsentStatus, Long> byStatus, ConsentStatus status) {
        return byStatus.getOrDefault(status, 0L);
    }
}
