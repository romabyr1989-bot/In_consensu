package ru.example.inconsensu.registry.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.example.inconsensu.common.api.ApiTime;
import ru.example.inconsensu.registry.application.ConsentEvidenceService;

/** Досье согласия в ответе API (FR-10.3). */
public record ConsentEvidenceResponse(
        UUID consentId,
        FormText form,
        FormItem item,
        Signature signature,
        List<EventEntry> events,
        String integrity,
        List<String> integrityProblems) {

    /** Точный текст версии формы и сверка контрольных сумм. */
    public record FormText(
            String code,
            Integer version,
            OffsetDateTime publishedAt,
            String text,
            String storedChecksum,
            String recalculatedChecksum,
            boolean checksumMatches) {}

    /**
     * Пункт формы, по которому дано согласие (FR-10.3).
     *
     * <p>Условия продублированы снимком в самом согласии (§8.5), но досье обязано показывать и то, что
     * было написано в пункте: именно его текст видел клиент.
     */
    public record FormItem(
            UUID id,
            String consentTypeCode,
            String consentTypeName,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            String validity,
            boolean mandatory) {}

    public record Signature(String signatureType, String signatureTypeRu, Map<String, Object> evidence) {}

    public record EventEntry(
            String eventType, String eventTypeRu, OffsetDateTime occurredAt, String actor, String hash) {}

    public static ConsentEvidenceResponse of(
            ConsentEvidenceService.Dossier dossier, Map<String, Object> maskedEvidence, ZoneId zone) {
        var consent = dossier.consent();
        var form = dossier.form();
        return new ConsentEvidenceResponse(
                consent.getId(),
                form == null
                        ? null
                        : new FormText(
                                form.getCode(),
                                form.getVersionNumber(),
                                ApiTime.at(form.getPublishedAt(), zone),
                                dossier.formText(),
                                dossier.storedChecksum(),
                                dossier.recalculatedChecksum(),
                                dossier.checksumMatches()),
                itemOf(dossier),
                new Signature(
                        consent.getSignatureType().name(),
                        consent.getSignatureType().nameRu(),
                        maskedEvidence),
                dossier.events().stream()
                        .map(event -> new EventEntry(
                                event.getEventType().name(),
                                event.getEventType().nameRu(),
                                ApiTime.at(event.getOccurredAt(), zone),
                                event.getActorId(),
                                event.getHash()))
                        .toList(),
                dossier.integrity().name(),
                dossier.integrityProblems().stream()
                        .map(problem -> problem.description())
                        .toList());
    }

    /** Пункт ищется в версии формы по идентификатору, сохранённому в согласии. */
    private static FormItem itemOf(ConsentEvidenceService.Dossier dossier) {
        if (dossier.form() == null || dossier.consent().getFormItemId() == null) {
            return null;
        }
        return dossier.form().getItems().stream()
                .filter(candidate -> candidate.getId().equals(dossier.consent().getFormItemId()))
                .findFirst()
                .map(candidate -> new FormItem(
                        candidate.getId(),
                        candidate.getConsentType().getCode(),
                        candidate.getConsentType().getNameRu(),
                        candidate.getText(),
                        candidate.getPurposes(),
                        candidate.getPdnCategories(),
                        candidate.getValidity(),
                        candidate.isMandatory()))
                .orElse(null);
    }
}
