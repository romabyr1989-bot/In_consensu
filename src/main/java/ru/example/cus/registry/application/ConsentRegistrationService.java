package ru.example.cus.registry.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.ConsentFormItem;
import ru.example.cus.catalog.domain.ConsentType;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.CusEvent;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.common.domain.FormStatus;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.common.error.ValidationErrorItem;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.domain.EvidenceValidator;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.registry.infrastructure.ConsentRepository;
import ru.example.cus.thirdparty.application.ThirdPartyService;
import ru.example.cus.thirdparty.domain.ThirdParty;

/** Регистрация согласий: одно или пакетом по одной форме за вызов (FR-4.1 … FR-4.4). */
@Service
public class ConsentRegistrationService {

    public static final String AGGREGATE_TYPE = "consent";

    /** FR-4.2: granted_at не может быть из будущего дальше, чем на этот допуск на рассинхрон часов. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /** Ключ идемпотентности запроса дополняется пунктом: одна форма может породить несколько согласий. */
    private static final String KEY_SEPARATOR = "#";

    public record ItemDecision(UUID formItemId, boolean accepted) {}

    public record RegistrationRequest(
            String subjectExternalId,
            SubjectService.SubjectForm subjectData,
            UUID formId,
            List<ItemDecision> items,
            Instant grantedAt,
            ConsentSource source,
            String sourceRef,
            SignatureType signatureType,
            Map<String, Object> evidence) {}

    public record RegistrationResult(List<Consent> created, List<UUID> declinedItems, boolean idempotentReplay) {}

    private final ConsentRepository consents;
    private final SubjectService subjects;
    private final ConsentFormService forms;
    private final ThirdPartyService thirdParties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConsentRegistrationService(
            ConsentRepository consents,
            SubjectService subjects,
            ConsentFormService forms,
            ThirdPartyService thirdParties,
            AuditService auditService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            Clock clock) {
        this.consents = consents;
        this.subjects = subjects;
        this.forms = forms;
        this.thirdParties = thirdParties;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public RegistrationResult register(String idempotencyKey, RegistrationRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Заголовок Idempotency-Key обязателен (FR-4.1)");
        }

        // FR-4.1: повторный запрос с тем же ключом возвращает исходный результат, а не создаёт дубли.
        List<Consent> existing = consents.findByIdempotencyKeyStartingWith(idempotencyKey + KEY_SEPARATOR);
        if (!existing.isEmpty()) {
            return new RegistrationResult(existing, List.of(), true);
        }

        Instant now = clock.instant();
        Instant grantedAt = request.grantedAt() == null ? now : request.grantedAt();
        if (grantedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Дата выражения согласия не может быть в будущем");
        }

        ConsentForm form = forms.get(request.formId());
        requireUsableForm(form, request.source(), grantedAt);

        SignatureType signatureType = request.signatureType();
        List<String> missing = EvidenceValidator.missingFields(signatureType, request.evidence());
        if (!missing.isEmpty()) {
            throw ApiException.validation(
                    "Неполный состав доказательств для способа подписания " + signatureType.nameRu(),
                    missing.stream()
                            .map(field -> new ValidationErrorItem("evidence." + field, "Поле обязательно (FR-4.2)"))
                            .toList());
        }

        Subject subject = resolveSubject(request);
        String evidenceJson = toJson(request.evidence());

        List<Consent> created = new ArrayList<>();
        List<UUID> declined = new ArrayList<>();

        for (ItemDecision decision : request.items()) {
            ConsentFormItem item = form.getItems().stream()
                    .filter(candidate -> candidate.getId().equals(decision.formItemId()))
                    .findFirst()
                    .orElseThrow(() ->
                            new ApiException(ErrorCode.VALIDATION_FAILED, "Пункт не принадлежит указанной форме"));

            if (!decision.accepted()) {
                // FR-4.1: отказ не создаёт согласия, но остаётся доказуемым фактом.
                declined.add(item.getId());
                auditService.record(
                        AGGREGATE_TYPE,
                        "declined:" + subject.getId() + ":" + item.getId(),
                        subject.getId(),
                        AuditEventType.DECLINED,
                        Map.of(
                                "formCode",
                                form.getCode(),
                                "typeCode",
                                item.getConsentType().getCode()));
                continue;
            }

            created.add(
                    registerItem(idempotencyKey, request, form, item, subject, grantedAt, signatureType, evidenceJson));
        }

        return new RegistrationResult(created, declined, false);
    }

    private Consent registerItem(
            String idempotencyKey,
            RegistrationRequest request,
            ConsentForm form,
            ConsentFormItem item,
            Subject subject,
            Instant grantedAt,
            SignatureType signatureType,
            String evidenceJson) {

        ConsentType type = item.getConsentType();
        if (!type.isActive()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Тип согласия «" + type.getNameRu() + "» деактивирован");
        }

        UUID thirdPartyId = item.getThirdPartyId();
        if (type.isRequiresThirdParty()) {
            if (thirdPartyId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Для передачи данных не указано третье лицо");
            }
            ThirdParty thirdParty = thirdParties.get(thirdPartyId);
            if (!thirdParty.canReceiveData(thirdParties.today())) {
                throw new ApiException(
                        ErrorCode.CONFLICT,
                        "Третье лицо «" + thirdParty.getName() + "» неактивно или договор с ним истёк (FR-7.1)");
            }
        }

        Consent consent = new Consent(
                UUID.randomUUID(),
                subject.getId(),
                type.getId(),
                form.getId(),
                item.getId(),
                form.getRenderedChecksum(),
                request.source(),
                request.sourceRef(),
                grantedAt,
                validUntil(grantedAt, item, type),
                thirdPartyId,
                item.getPdnCategories(),
                item.getPurposes(),
                signatureType,
                evidenceJson,
                idempotencyKey + KEY_SEPARATOR + item.getId());

        Consent saved = consents.save(consent);
        supersedePrevious(saved);

        auditService.record(
                AGGREGATE_TYPE,
                saved.getId().toString(),
                subject.getId(),
                AuditEventType.GRANTED,
                describe(saved, form, type));
        // §8.6: событие ложится в outbox в этой же транзакции — иначе потребители узнают о согласии,
        // которого нет, либо не узнают о том, которое есть.
        events.publishEvent(CusEvent.of(
                AGGREGATE_TYPE,
                saved.getId().toString(),
                EventTypes.CONSENT_GRANTED,
                subject.getId(),
                Map.of("typeCode", type.getCode(), "consentId", saved.getId().toString())));
        return saved;
    }

    /** FR-4.3: предыдущее эффективное согласие той же пары «тип + третье лицо» становится заменённым. */
    private void supersedePrevious(Consent newer) {
        consents.findEffectiveForSupersede(
                        newer.getSubjectId(), newer.getConsentTypeId(), newer.getThirdPartyId(), newer.getId())
                .forEach(previous -> {
                    previous.supersedeBy(newer);
                    consents.save(previous);
                    auditService.record(
                            AGGREGATE_TYPE,
                            previous.getId().toString(),
                            previous.getSubjectId(),
                            AuditEventType.SUPERSEDED,
                            Map.of("supersededBy", newer.getId().toString()));
                    events.publishEvent(CusEvent.of(
                            AGGREGATE_TYPE,
                            previous.getId().toString(),
                            EventTypes.CONSENT_SUPERSEDED,
                            previous.getSubjectId(),
                            Map.of("supersededByConsentId", newer.getId().toString())));
                });
    }

    /** FR-4.3: срок берётся из пункта формы, иначе из типа, иначе согласие бессрочное. */
    static Instant validUntil(Instant grantedAt, ConsentFormItem item, ConsentType type) {
        String validity = item.getValidity() != null ? item.getValidity() : type.getDefaultValidity();
        if (validity == null || validity.isBlank()) {
            return null;
        }
        if (validity.startsWith("PT")) {
            return grantedAt.plus(Duration.parse(validity));
        }
        return grantedAt
                .atZone(java.time.ZoneOffset.UTC)
                .plus(Period.parse(validity))
                .toInstant();
    }

    /** FR-2.3: регистрировать можно только по опубликованной действующей версии; импорт — исключение. */
    private void requireUsableForm(ConsentForm form, ConsentSource source, Instant grantedAt) {
        if (form.getStatus() == FormStatus.PUBLISHED) {
            if (!form.isEffectiveAt(grantedAt)) {
                throw new ApiException(
                        ErrorCode.CONFLICT, "Версия формы не действовала на дату выражения согласия (FR-2.3)");
            }
            return;
        }
        boolean historicalImport = source == ConsentSource.CLIENT_BASE_IMPORT;
        if (historicalImport && form.getStatus() == FormStatus.ARCHIVED && form.isEffectiveAt(grantedAt)) {
            return;
        }
        throw new ApiException(
                ErrorCode.CONFLICT,
                "Согласие регистрируется только по опубликованной форме; текущий статус — "
                        + form.getStatus().nameRu());
    }

    private Subject resolveSubject(RegistrationRequest request) {
        if (request.subjectData() != null) {
            return subjects.upsert(request.subjectData());
        }
        return subjects.findByExternalId(request.subjectExternalId())
                .orElseThrow(() -> ApiException.notFound(
                        "Субъект с внешним идентификатором " + request.subjectExternalId() + " не найден"));
    }

    private String toJson(Map<String, Object> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence == null ? Map.of() : evidence);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Доказательства должны быть корректным JSON-объектом");
        }
    }

    /** В журнал уходят коды и идентификаторы; чувствительные поля доказательства маскируются (NFR-3). */
    private Map<String, Object> describe(Consent consent, ConsentForm form, ConsentType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typeCode", type.getCode());
        payload.put("formCode", form.getCode());
        payload.put("formVersion", form.getVersionNumber());
        payload.put("formChecksum", consent.getFormChecksum());
        payload.put("source", consent.getSource().name());
        payload.put("sourceRef", consent.getSourceRef());
        payload.put("validUntil", String.valueOf(consent.getValidUntil()));
        payload.put("signatureType", consent.getSignatureType().name());
        payload.put("pdnCategories", consent.getPdnCategories());
        payload.put(
                "thirdPartyId",
                Optional.ofNullable(consent.getThirdPartyId())
                        .map(UUID::toString)
                        .orElse(null));
        return payload;
    }

    /**
     * Регистрация исторического согласия из импорта (FR-4.5, FR-4.6).
     *
     * <p>Отличается от обычной регистрации тем, что срок действия приходит из файла, а не считается из формы:
     * в выгрузке он уже зафиксирован, и пересчёт исказил бы историю. Способ подписания — IMPORTED_LEGACY.
     */
    @Transactional
    public Consent registerImported(ImportedConsent imported) {
        Optional<Consent> existing = consents.findByIdempotencyKey(imported.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        List<String> missing = EvidenceValidator.missingFields(SignatureType.IMPORTED_LEGACY, imported.evidence());
        if (!missing.isEmpty()) {
            throw ApiException.validation(
                    "Неполный состав доказательств для импортированного согласия",
                    missing.stream()
                            .map(field -> new ValidationErrorItem("evidence." + field, "Поле обязательно (FR-4.2)"))
                            .toList());
        }

        Consent consent = new Consent(
                UUID.randomUUID(),
                imported.subjectId(),
                imported.consentTypeId(),
                imported.formId(),
                imported.formItemId(),
                imported.formChecksum(),
                imported.source(),
                imported.sourceRef(),
                imported.grantedAt(),
                imported.validUntil(),
                imported.thirdPartyId(),
                imported.pdnCategories(),
                imported.purposes(),
                SignatureType.IMPORTED_LEGACY,
                toJson(imported.evidence()),
                imported.idempotencyKey());

        Consent saved = consents.save(consent);
        supersedePrevious(saved);

        auditService.record(
                AGGREGATE_TYPE,
                saved.getId().toString(),
                saved.getSubjectId(),
                AuditEventType.IMPORTED,
                Map.of(
                        "source", saved.getSource().name(),
                        "sourceRef", String.valueOf(saved.getSourceRef()),
                        "importJobId", String.valueOf(imported.evidence().get("importJobId"))));
        return saved;
    }

    /** Данные исторического согласия, уже разрешённые импортом в идентификаторы. */
    public record ImportedConsent(
            UUID subjectId,
            UUID consentTypeId,
            UUID formId,
            UUID formItemId,
            String formChecksum,
            ConsentSource source,
            String sourceRef,
            Instant grantedAt,
            Instant validUntil,
            UUID thirdPartyId,
            List<String> pdnCategories,
            List<String> purposes,
            Map<String, Object> evidence,
            String idempotencyKey) {}
}
