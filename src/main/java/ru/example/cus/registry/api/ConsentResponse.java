package ru.example.cus.registry.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import ru.example.cus.common.api.ApiTime;
import ru.example.cus.registry.application.ConsentQueryService;

/** Согласие в ответе API со статусом на момент чтения (FR-5.1, FR-5.3, Приложение A). */
public record ConsentResponse(
        UUID id,
        UUID subjectId,
        UUID typeId,
        String typeCode,
        String typeNameRu,
        UUID thirdPartyId,
        String thirdPartyName,
        List<String> pdnCategories,
        List<String> purposes,
        String status,
        String statusText,
        Long daysLeft,
        OffsetDateTime grantedAt,
        OffsetDateTime validUntil,
        OffsetDateTime revokedAt,
        String revocationSource,
        String source,
        String sourceRef,
        String signatureType,
        FormRef form) {

    /** Ссылка на точный текст, по которому дано согласие (FR-1.6, FR-5.1). */
    public record FormRef(UUID id, String code, Integer version, String checksum) {}

    public static ConsentResponse of(
            ConsentQueryService.ConsentView view,
            ZoneId zone,
            String typeCode,
            String typeNameRu,
            String thirdPartyName,
            String formCode,
            Integer formVersion) {
        var consent = view.consent();
        return new ConsentResponse(
                consent.getId(),
                consent.getSubjectId(),
                consent.getConsentTypeId(),
                typeCode,
                typeNameRu,
                consent.getThirdPartyId(),
                thirdPartyName,
                consent.getPdnCategories(),
                consent.getPurposes(),
                view.status().name(),
                view.statusText(),
                view.daysLeft(),
                ApiTime.at(consent.getGrantedAt(), zone),
                ApiTime.at(consent.getValidUntil(), zone),
                ApiTime.at(consent.getRevokedAt(), zone),
                consent.getRevocationSource() == null
                        ? null
                        : consent.getRevocationSource().name(),
                consent.getSource().name(),
                consent.getSourceRef(),
                consent.getSignatureType().name(),
                new FormRef(consent.getFormId(), formCode, formVersion, consent.getFormChecksum()));
    }
}
