package ru.example.inconsensu.ui.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.domain.PdnCategory;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Модель экранов третьих лиц (UI-11).
 *
 * <p>Строки собираются здесь, а не в шаблоне: §5 запрещает логику в представлении, а UI-0.4 — коды
 * справочников вместо русских названий. До этого таблица печатала коды категорий ПДн, а срок договора
 * был виден только бейджем «истекает через N дней», без самой даты.
 */
@Service
public class UiThirdPartyViewService {

    /** Порог, с которого договор считается истекающим: тот же, что у счётчиков каталога (FR-3.4). */
    private static final int EXPIRY_WARNING_DAYS = 30;

    /**
     * @param contractBadge подпись бейджа договора; пустая, если срок не близко
     * @param contractBadgeKind класс бейджа Bootstrap: danger для истёкшего, warning для истекающего
     */
    public record PartyRow(
            UUID id,
            String name,
            String inn,
            String roleRu,
            String contractNumber,
            String contractUntil,
            String contractBadge,
            String contractBadgeKind,
            String categoriesRu,
            boolean active,
            long consentsActive,
            long consentsExpiring,
            long consentsRevoked) {}

    /** @param downloadable ссылка «Скачать» показывается, пока не истёк TTL (UI-11) */
    public record ExportRow(
            UUID id,
            String requestedAt,
            String requestedBy,
            String formatRu,
            int recordsCount,
            String expiresAt,
            boolean downloadable) {}

    private final ThirdPartyService thirdParties;
    private final PartnerExportService exports;
    private final PdnCategoryService pdnCategories;
    private final CatalogStatsService stats;
    private final UiFormats formats;
    private final Clock clock;

    public UiThirdPartyViewService(
            ThirdPartyService thirdParties,
            PartnerExportService exports,
            PdnCategoryService pdnCategories,
            CatalogStatsService stats,
            UiFormats formats,
            Clock clock) {
        this.thirdParties = thirdParties;
        this.exports = exports;
        this.pdnCategories = pdnCategories;
        this.stats = stats;
        this.formats = formats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PartyRow> rows() {
        return rows(false);
    }

    /** @param expiringContractsOnly оставить только партнёров с истекающим или истёкшим договором (UI-2) */
    @Transactional(readOnly = true)
    public List<PartyRow> rows(boolean expiringContractsOnly) {
        List<PartyRow> all = allRows();
        return expiringContractsOnly
                ? all.stream().filter(row -> !row.contractBadge().isEmpty()).toList()
                : all;
    }

    private List<PartyRow> allRows() {
        LocalDate today = thirdParties.today();
        Map<String, String> names = categoryNamesByCode();
        Map<UUID, CatalogStatsService.ThirdPartyStats> counts = stats.byThirdParty().stream()
                .collect(Collectors.toMap(CatalogStatsService.ThirdPartyStats::id, Function.identity()));
        return thirdParties.list(Pageable.unpaged()).getContent().stream()
                .map(party -> {
                    CatalogStatsService.ThirdPartyStats partyStats = counts.get(party.getId());
                    return new PartyRow(
                            party.getId(),
                            party.getName(),
                            party.getInn(),
                            party.getRole().nameRu(),
                            party.getContractNumber(),
                            formats.date(party.getContractValidUntil()),
                            contractBadge(party, today),
                            contractBadgeKind(party, today),
                            joinNames(party.getAllowedPdnCategories(), names),
                            party.isActive(),
                            partyStats == null ? 0L : partyStats.active(),
                            partyStats == null ? 0L : partyStats.expiringSoon(),
                            partyStats == null ? 0L : partyStats.revoked());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExportRow> exports(UUID thirdPartyId) {
        return exports.listFor(thirdPartyId).stream()
                .map(export -> new ExportRow(
                        export.getId(),
                        formats.dateTime(export.getRequestedAt()),
                        export.getRequestedBy(),
                        "json".equals(export.getFormat()) ? "JSON" : "CSV",
                        export.getRecordsCount(),
                        formats.dateTime(export.getExpiresAt()),
                        export.isDownloadable(clock.instant())))
                .toList();
    }

    /** Русские названия категорий ПДн подряд: их показывает и таблица, и диалог выгрузки. */
    @Transactional(readOnly = true)
    public String categoryNames(Collection<String> codes) {
        return joinNames(codes, categoryNamesByCode());
    }

    private Map<String, String> categoryNamesByCode() {
        return pdnCategories.activeCategories().stream()
                .collect(Collectors.toMap(PdnCategory::getCode, PdnCategory::getNameRu, (first, second) -> first));
    }

    private static String joinNames(Collection<String> codes, Map<String, String> names) {
        if (codes == null || codes.isEmpty()) {
            return "не заданы";
        }
        return codes.stream().map(code -> names.getOrDefault(code, code)).collect(Collectors.joining(", "));
    }

    private static String contractBadge(ThirdParty party, LocalDate today) {
        LocalDate until = party.getContractValidUntil();
        if (until == null) {
            return "";
        }
        if (until.isBefore(today)) {
            return "истёк";
        }
        Long days = party.daysUntilContractEnds(today);
        return days != null && days <= EXPIRY_WARNING_DAYS ? "истекает через " + days + " дн." : "";
    }

    private static String contractBadgeKind(ThirdParty party, LocalDate today) {
        LocalDate until = party.getContractValidUntil();
        if (until == null) {
            return "";
        }
        return until.isBefore(today) ? "bg-danger" : "bg-warning text-dark";
    }
}
