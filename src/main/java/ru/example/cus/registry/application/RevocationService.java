package ru.example.cus.registry.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.catalog.application.ConsentTypeService;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.CusEvent;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.iam.application.OperatorSettingsService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.infrastructure.ConsentRepository;

/**
 * Отзыв согласия (§7.8).
 *
 * <p>Отзыв необратим и вступает в силу немедленно: статус, каскад и запрет каналов происходят в одной
 * транзакции (FR-8.3). Кэша нет нигде по дороге — иначе «нельзя» появилось бы с задержкой, а это звонок
 * клиенту, который только что запретил звонить.
 */
@Service
public class RevocationService {

    /** ч. 5 ст. 21 152-ФЗ: обработку нужно прекратить в течение 30 дней после отзыва (FR-8.5). */
    public static final Duration PROCESSING_STOP_PERIOD = Duration.ofDays(30);

    private static final String CASCADE_SETTING = "cus.revocation.cascade-enabled";

    /** Результат отзыва: само согласие плюс погашенные каскадом (FR-8.4). */
    public record RevocationResult(
            Consent revoked,
            List<Consent> cascaded,
            Instant revokedAt,
            Instant processingStopDeadline,
            String caseNumber) {

        public List<Consent> all() {
            List<Consent> everything = new ArrayList<>();
            everything.add(revoked);
            everything.addAll(cascaded);
            return everything;
        }
    }

    private final ConsentRepository consents;
    private final ConsentTypeService types;
    private final OperatorSettingsService settings;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RevocationService(
            ConsentRepository consents,
            ConsentTypeService types,
            OperatorSettingsService settings,
            AuditService auditService,
            ApplicationEventPublisher events,
            Clock clock) {
        this.consents = consents;
        this.types = types;
        this.settings = settings;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * FR-8.2, FR-8.3: отзыв по обращению клиента.
     *
     * <p>Идемпотентен: повторный отзыв уже отозванного согласия ничего не меняет и не считается ошибкой —
     * клиент, нажавший кнопку дважды, не должен получить отказ.
     */
    @Transactional
    public RevocationResult revoke(
            UUID consentId, String reason, RevocationSource source, String caseNumber, Map<String, Object> evidence) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> ApiException.notFound("Согласие не найдено"));
        return revokeConsent(consent, reason, source, caseNumber, evidence);
    }

    /** FR-8.1: требование прекратить рекламу — все рекламные типы гасятся разом. */
    @Transactional
    public List<RevocationResult> revokeAllAdvertising(
            UUID subjectId, String reason, RevocationSource source, String caseNumber) {
        Set<UUID> advertisingTypes = types.advertisingTypeIds();
        List<RevocationResult> results = new ArrayList<>();

        for (Consent consent : consents.findEffectiveBySubject(subjectId)) {
            if (advertisingTypes.contains(consent.getConsentTypeId())) {
                results.add(revokeConsent(consent, reason, source, caseNumber, Map.of()));
            }
        }
        return results;
    }

    private RevocationResult revokeConsent(
            Consent consent, String reason, RevocationSource source, String caseNumber, Map<String, Object> evidence) {
        Instant now = clock.instant();

        if (consent.getRevokedAt() != null) {
            // Уже отозвано: возвращаем прежний результат, ничего не трогая (FR-8.3).
            return new RevocationResult(
                    consent,
                    List.of(),
                    consent.getRevokedAt(),
                    consent.getRevokedAt().plus(PROCESSING_STOP_PERIOD),
                    caseNumber);
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите причину отзыва согласия (FR-8.2)");
        }

        consent.revoke(now, reason, source);
        consents.save(consent);
        recordRevocation(consent, source, caseNumber, evidence, null);

        List<Consent> cascaded = cascadeEnabled() ? cascadeFrom(consent, now, caseNumber) : List.of();

        return new RevocationResult(consent, cascaded, now, now.plus(PROCESSING_STOP_PERIOD), caseNumber);
    }

    /**
     * FR-8.4: отзыв типа, от которого зависят другие, гасит и их.
     *
     * <p>Так отзыв базового согласия на обработку ПДн автоматически закрывает рекламу, передачи и
     * распространение: оставить их означало бы обрабатывать данные без основания.
     */
    private List<Consent> cascadeFrom(Consent origin, Instant now, String caseNumber) {
        Set<UUID> dependentTypes = types.dependentTypeIds(origin.getConsentTypeId());
        if (dependentTypes.isEmpty()) {
            return List.of();
        }

        List<Consent> cascaded = new ArrayList<>();
        for (Consent dependent : consents.findEffectiveBySubject(origin.getSubjectId())) {
            if (!dependentTypes.contains(dependent.getConsentTypeId()) || dependent.getRevokedAt() != null) {
                continue;
            }
            dependent.revoke(
                    now, "Каскадный отзыв: отозвано согласие, от которого зависит этот тип", RevocationSource.CASCADE);
            consents.save(dependent);
            recordRevocation(dependent, RevocationSource.CASCADE, caseNumber, Map.of(), origin.getId());
            cascaded.add(dependent);
        }
        return cascaded;
    }

    /** Заранее рассчитанный список того, что погаснет вместе с согласием — для диалога подтверждения UI-5. */
    @Transactional(readOnly = true)
    public List<Consent> previewCascade(UUID consentId) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> ApiException.notFound("Согласие не найдено"));
        if (!cascadeEnabled()) {
            return List.of();
        }
        Set<UUID> dependentTypes = types.dependentTypeIds(consent.getConsentTypeId());
        return consents.findEffectiveBySubject(consent.getSubjectId()).stream()
                .filter(candidate -> dependentTypes.contains(candidate.getConsentTypeId()))
                .toList();
    }

    private boolean cascadeEnabled() {
        String configured = settings.value(CASCADE_SETTING);
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured.trim());
    }

    private void recordRevocation(
            Consent consent, RevocationSource source, String caseNumber, Map<String, Object> evidence, UUID causedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revocationSource", source.name());
        payload.put("caseNumber", caseNumber);
        // ч. 5 ст. 21 152-ФЗ: получатели события узнают, до какой даты обязаны прекратить обработку (FR-8.5).
        payload.put(
                "processingStopDeadline",
                consent.getRevokedAt().plus(PROCESSING_STOP_PERIOD).toString());
        if (causedBy != null) {
            payload.put("causedByConsentId", causedBy.toString());
        }
        if (evidence != null && !evidence.isEmpty()) {
            payload.put("evidence", evidence);
        }
        auditService.record(
                ConsentRegistrationService.AGGREGATE_TYPE,
                consent.getId().toString(),
                consent.getSubjectId(),
                AuditEventType.REVOKED,
                payload);
        // FR-8.5: получатели узнают, до какой даты обязаны прекратить обработку. Наружу уходит не тот же
        // payload, что в журнал: доказательства отзыва содержат телефон, IP и адрес — в webhook им нельзя (NFR-3).
        Map<String, Object> external = new LinkedHashMap<>(payload);
        external.remove("evidence");
        events.publishEvent(CusEvent.of(
                ConsentRegistrationService.AGGREGATE_TYPE,
                consent.getId().toString(),
                EventTypes.CONSENT_REVOKED,
                consent.getSubjectId(),
                external));
    }
}
