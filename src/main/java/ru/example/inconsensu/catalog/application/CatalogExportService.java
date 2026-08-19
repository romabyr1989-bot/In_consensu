package ru.example.inconsensu.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.catalog.infrastructure.ConsentFormRepository;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Выгрузка каталога: типы, формы и пункты форм (FR-3.3).
 *
 * <p>Снимок собирается целиком, а формат выбирает контроллер: json отдаёт вложенную структуру, csv —
 * одну из трёх таблиц. Плоские строки готовятся здесь, чтобы обе выгрузки описывали каталог одинаково.
 */
@Service
public class CatalogExportService {

    /** Часть выгрузки: у типов, форм и пунктов разный набор колонок, одной csv-таблицей их не описать. */
    public enum Part {
        TYPES,
        FORMS,
        ITEMS
    }

    public record CatalogSnapshot(Instant generatedAt, List<TypeRow> types, List<FormRow> forms) {}

    /** @param expiringSoon согласия этого типа, срок которых истекает в ближайшие 30 дней (FR-3.4) */
    public record TypeRow(
            String code,
            String nameRu,
            String category,
            boolean requiresThirdParty,
            String defaultValidity,
            boolean active,
            long activeConsents,
            long expiringConsents,
            long expiredConsents,
            long revokedConsents,
            long expiringSoon) {}

    public record FormRow(
            UUID id,
            String code,
            int version,
            String title,
            String status,
            Instant validFrom,
            Instant validTo,
            Instant publishedAt,
            String checksum,
            List<String> sourceChannels,
            List<ItemRow> items) {}

    public record ItemRow(
            UUID id,
            String formCode,
            int formVersion,
            int sortOrder,
            String consentTypeCode,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            String thirdPartyInn,
            String thirdPartyName,
            String validity,
            boolean mandatory) {}

    private final CatalogStatsService stats;
    private final ConsentFormRepository forms;
    private final Clock clock;

    public CatalogExportService(CatalogStatsService stats, ConsentFormRepository forms, Clock clock) {
        this.stats = stats;
        this.forms = forms;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogSnapshot snapshot() {
        Instant now = clock.instant();
        Map<String, CatalogStatsService.TypeStats> counts = stats.byType().stream()
                .collect(Collectors.toMap(CatalogStatsService.TypeStats::code, Function.identity()));
        Map<UUID, ThirdParty> parties =
                stats.allThirdParties().stream().collect(Collectors.toMap(ThirdParty::getId, p -> p));

        List<TypeRow> typeRows =
                stats.activeTypes().stream().map(type -> typeRow(type, counts)).toList();
        List<FormRow> formRows = forms.findAllWithItems().stream()
                .sorted(Comparator.comparing(ConsentForm::getCode).thenComparingInt(ConsentForm::getVersionNumber))
                .map(form -> formRow(form, parties))
                .toList();
        return new CatalogSnapshot(now, typeRows, formRows);
    }

    private TypeRow typeRow(ConsentType type, Map<String, CatalogStatsService.TypeStats> counts) {
        CatalogStatsService.TypeStats typeStats = counts.get(type.getCode());
        return new TypeRow(
                type.getCode(),
                type.getNameRu(),
                type.getCategory().name(),
                type.isRequiresThirdParty(),
                type.getDefaultValidity(),
                type.isActive(),
                typeStats == null ? 0 : typeStats.active(),
                typeStats == null ? 0 : typeStats.expiring(),
                typeStats == null ? 0 : typeStats.expired(),
                typeStats == null ? 0 : typeStats.revoked(),
                typeStats == null ? 0 : typeStats.expiringSoon());
    }

    private FormRow formRow(ConsentForm form, Map<UUID, ThirdParty> parties) {
        List<ItemRow> items = form.getItems().stream()
                .sorted(Comparator.comparingInt(ConsentFormItem::getSortOrder))
                .map(item -> itemRow(form, item, parties))
                .toList();
        return new FormRow(
                form.getId(),
                form.getCode(),
                form.getVersionNumber(),
                form.getTitle(),
                form.getStatus().name(),
                form.getValidFrom(),
                form.getValidTo(),
                form.getPublishedAt(),
                form.getRenderedChecksum(),
                form.getSourceChannels().stream().map(Enum::name).toList(),
                items);
    }

    private ItemRow itemRow(ConsentForm form, ConsentFormItem item, Map<UUID, ThirdParty> parties) {
        ThirdParty party = item.getThirdPartyId() == null ? null : parties.get(item.getThirdPartyId());
        return new ItemRow(
                item.getId(),
                form.getCode(),
                form.getVersionNumber(),
                item.getSortOrder(),
                item.getConsentType().getCode(),
                item.getText(),
                item.getPurposes(),
                item.getPdnCategories(),
                party == null ? null : party.getInn(),
                party == null ? null : party.getName(),
                item.getValidity(),
                item.isMandatory());
    }
}
