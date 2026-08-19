package ru.example.cus.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.catalog.domain.ConsentCountsPort;
import ru.example.cus.catalog.domain.ConsentType;
import ru.example.cus.thirdparty.application.ThirdPartyService;

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
            List<TypeStats> byType) {}

    public record TypeStats(String code, String nameRu, long active, long revoked) {}

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
        List<TypeStats> byType = types.activeTypes().stream()
                .map(type -> new TypeStats(
                        type.getCode(),
                        type.getNameRu(),
                        consentCounts.activeConsentsOfType(type.getId()),
                        consentCounts.revokedConsentsOfType(type.getId())))
                .toList();

        return new CatalogStats(
                consentCounts.activeConsents(),
                consentCounts.expiringConsents(clock.instant(), clock.instant().plus(WINDOW)),
                consentCounts.revokedConsentsSince(clock.instant().minus(WINDOW)),
                forms.awaitingDecision().size(),
                forms.publishedCount(),
                thirdParties.contractsEndingWithin((int) WINDOW.toDays()).size(),
                byType);
    }

    /** Активные типы для экспорта каталога: он отдаёт те же счётчики построчно. */
    @Transactional(readOnly = true)
    public List<ConsentType> activeTypes() {
        return types.activeTypes();
    }
}
