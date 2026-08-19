package ru.example.inconsensu.catalog.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.api.ApiTime;

/** Форма согласия в ответе API (FR-1.2, FR-3.2, UI-7, UI-10). */
public record ConsentFormResponse(
        UUID id,
        String code,
        int version,
        String title,
        String status,
        String statusRu,
        String body,
        String processingActions,
        String revocationProcedure,
        List<String> sourceChannels,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        OffsetDateTime submittedAt,
        OffsetDateTime publishedAt,
        String checksum,
        UUID previousVersionId,
        List<ItemResponse> items) {

    public record ItemResponse(
            UUID id,
            int sortOrder,
            String consentTypeCode,
            String consentTypeNameRu,
            String category,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            UUID thirdPartyId,
            String validity,
            boolean mandatory) {

        static ItemResponse of(ConsentFormItem item) {
            return new ItemResponse(
                    item.getId(),
                    item.getSortOrder(),
                    item.getConsentType().getCode(),
                    item.getConsentType().getNameRu(),
                    item.getConsentType().getCategory().name(),
                    item.getText(),
                    item.getPurposes(),
                    item.getPdnCategories(),
                    item.getThirdPartyId(),
                    item.getValidity(),
                    item.isMandatory());
        }
    }

    public static ConsentFormResponse of(ConsentForm form, ZoneId zone) {
        return new ConsentFormResponse(
                form.getId(),
                form.getCode(),
                form.getVersionNumber(),
                form.getTitle(),
                form.getStatus().name(),
                form.getStatus().nameRu(),
                form.getBody(),
                form.getProcessingActions(),
                form.getRevocationProcedure(),
                form.getSourceChannels().stream().map(Enum::name).toList(),
                ApiTime.at(form.getValidFrom(), zone),
                ApiTime.at(form.getValidTo(), zone),
                ApiTime.at(form.getSubmittedAt(), zone),
                ApiTime.at(form.getPublishedAt(), zone),
                form.getRenderedChecksum(),
                form.getPreviousVersion() == null
                        ? null
                        : form.getPreviousVersion().getId(),
                form.getItems().stream().map(ItemResponse::of).toList());
    }

    /**
     * Краткая строка для списков каталога: без тела формы и без пунктов (FR-3.1).
     *
     * <p>Пункты здесь не читаются намеренно: список форм выбирается постранично, без выборки коллекции,
     * и обращение к ней вне транзакции стоило бы либо LazyInitializationException, либо запроса на каждую
     * строку списка.
     */
    public static ConsentFormResponse summary(ConsentForm form, ZoneId zone) {
        return new ConsentFormResponse(
                form.getId(),
                form.getCode(),
                form.getVersionNumber(),
                form.getTitle(),
                form.getStatus().name(),
                form.getStatus().nameRu(),
                null,
                null,
                null,
                form.getSourceChannels().stream().map(Enum::name).toList(),
                ApiTime.at(form.getValidFrom(), zone),
                ApiTime.at(form.getValidTo(), zone),
                ApiTime.at(form.getSubmittedAt(), zone),
                ApiTime.at(form.getPublishedAt(), zone),
                form.getRenderedChecksum(),
                form.getPreviousVersion() == null
                        ? null
                        : form.getPreviousVersion().getId(),
                List.of());
    }
}
