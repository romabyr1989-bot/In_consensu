package ru.example.inconsensu.registry.application;

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
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.CusEvent;
import ru.example.inconsensu.common.domain.EventTypes;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.error.ValidationErrorItem;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.registry.domain.EvidenceValidator;
import ru.example.inconsensu.registry.domain.RegistrationReceipt;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;
import ru.example.inconsensu.registry.infrastructure.RegistrationReceiptRepository;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

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
    private final RegistrationReceiptRepository receipts;
    private final ConsentQueryService queries;
    private final SubjectService subjects;
    private final ConsentFormService forms;
    private final ThirdPartyService thirdParties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConsentRegistrationService(
            ConsentRepository consents,
            RegistrationReceiptRepository receipts,
            ConsentQueryService queries,
            SubjectService subjects,
            ConsentFormService forms,
            ThirdPartyService thirdParties,
            AuditService auditService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            Clock clock) {
        this.consents = consents;
        this.receipts = receipts;
        this.queries = queries;
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
        // Признак обработки — квитанция, а не наличие согласий: запрос, все пункты которого отклонены,
        // согласий не создаёт, и по ним повтор не отличить от первого обращения.
        Optional<RegistrationReceipt> receipt = receipts.findByIdempotencyKey(idempotencyKey);
        if (receipt.isPresent()) {
            List<Consent> existing = consents.findByIdempotencyKeyStartingWith(idempotencyKey + KEY_SEPARATOR);
            return new RegistrationResult(existing, receipt.get().getDeclinedItemIds(), true);
        }

        Instant now = clock.instant();
        Instant grantedAt = request.grantedAt() == null ? now : request.grantedAt();
        if (grantedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Дата выражения согласия не может быть в будущем");
        }

        ConsentForm form = forms.get(request.formId());
        requireUsableForm(form, request.source(), grantedAt);

        SignatureType signatureType = request.signatureType();
        requireValidEvidence(signatureType, request.evidence());

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

        receipts.save(new RegistrationReceipt(UUID.randomUUID(), idempotencyKey, subject.getId(), declined, now));
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

        consent.refreshStatus(clock.instant(), queries.expiringDays());
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
                grantedPayload(type, saved)));
        return saved;
    }

    /** Состав события о выдаче: типа хватает и правилу уведомления (FR-9.1), и письму (FR-8.5). */
    private static Map<String, Object> grantedPayload(ConsentType type, Consent saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typeCode", type.getCode());
        payload.put("typeName", type.getNameRu());
        payload.put("consentTypeId", type.getId().toString());
        payload.put("consentId", saved.getId().toString());
        if (saved.getThirdPartyId() != null) {
            payload.put("thirdPartyId", saved.getThirdPartyId().toString());
        }
        return payload;
    }

    /**
     * FR-4.3: предыдущее эффективное согласие той же пары «тип + третье лицо» становится заменённым.
     *
     * <p>Сравниваются даты выражения согласия, а не порядок записи: строка импорта или регистрация задним
     * числом не должна погасить более свежее согласие клиента. Если новое согласие старше существующего,
     * заменённым становится оно само.
     */
    private void supersedePrevious(Consent newer) {
        consents.findEffectiveForSupersede(
                        newer.getSubjectId(), newer.getConsentTypeId(), newer.getThirdPartyId(), newer.getId())
                .forEach(previous -> {
                    if (previous.getGrantedAt().isAfter(newer.getGrantedAt())) {
                        newer.supersedeBy(previous);
                        consents.save(newer);
                        auditService.record(
                                AGGREGATE_TYPE,
                                newer.getId().toString(),
                                newer.getSubjectId(),
                                AuditEventType.SUPERSEDED,
                                Map.of(
                                        "supersededBy",
                                        previous.getId().toString(),
                                        "reason",
                                        "зарегистрировано задним числом"));
                        // §8.6 и FR-9.4: внешний эффект уходит через outbox в этой же транзакции. Без
                        // события потребитель видел по этому согласию только consent.granted и наивно
                        // считал бы его эффективным, хотя оно уже замещено.
                        events.publishEvent(CusEvent.of(
                                AGGREGATE_TYPE,
                                newer.getId().toString(),
                                EventTypes.CONSENT_SUPERSEDED,
                                newer.getSubjectId(),
                                Map.of("supersededByConsentId", previous.getId().toString())));
                        return;
                    }
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

    /**
     * Состав и формат доказательств по способу подписания (FR-4.2).
     *
     * <p>Формат проверяется отдельно от наличия: телефон, записанный как «8 916 …», формально заполнен,
     * но сопоставить его с абонентом нельзя — как доказательство он не работает.
     */
    private static void requireValidEvidence(SignatureType signatureType, Map<String, Object> evidence) {
        List<ValidationErrorItem> problems = new ArrayList<>();
        EvidenceValidator.missingFields(signatureType, evidence)
                .forEach(field ->
                        problems.add(new ValidationErrorItem("evidence." + field, "Поле обязательно (FR-4.2)")));
        EvidenceValidator.malformedFields(signatureType, evidence)
                .forEach(field -> problems.add(new ValidationErrorItem(
                        "evidence." + field, "Укажите телефон в формате E.164, например +79160000041 (FR-4.2)")));
        if (!problems.isEmpty()) {
            problems.sort(java.util.Comparator.comparing(ValidationErrorItem::field));
            throw ApiException.validation(
                    "Неверный состав доказательств для способа подписания " + signatureType.nameRu(), problems);
        }
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

    /**
     * FR-2.3: регистрировать можно только по опубликованной действующей версии; импорт — исключение.
     *
     * <p>Для импорта исторических согласий окно действия опубликованной версии не проверяется: формы
     * заводятся в системе при внедрении и получают `valid_from` = момент публикации, поэтому иначе
     * отвергался бы любой перенос ранее собранных согласий. Статус проверяется в обоих случаях —
     * ссылка на черновик недопустима: его никто не согласовывал и не публиковал. Вопрос о том, нужно ли
     * заводить архивные версии задним числом, вынесен в OPEN_QUESTIONS (вопрос 18).
     */
    /**
     * Та же проверка пригодности формы, доступная до записи (FR-2.3, NFR-1).
     *
     * <p>Нужна пакетному импорту: он обязан отклонить строку, ничего не записав, а без этого отказ
     * приходился бы на середину строки — субъект уже создан, согласие ещё нет.
     */
    @Transactional(readOnly = true)
    public void requireImportableForm(ConsentForm form, Instant grantedAt) {
        if (form != null) {
            requireUsableForm(form, ConsentSource.CLIENT_BASE_IMPORT, grantedAt);
        }
    }

    private void requireUsableForm(ConsentForm form, ConsentSource source, Instant grantedAt) {
        boolean historicalImport = source == ConsentSource.CLIENT_BASE_IMPORT;
        if (form.getStatus() == FormStatus.PUBLISHED) {
            if (!historicalImport && !form.isEffectiveAt(grantedAt)) {
                throw new ApiException(
                        ErrorCode.CONFLICT, "Версия формы не действовала на дату выражения согласия (FR-2.3)");
            }
            return;
        }
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
            // Контакты дописываются, а не заменяются: в запросе на регистрацию приходит тот контакт, по
            // которому получено согласие, и замена стирала бы остальные — телефон клиента исчезал после
            // согласия, оформленного по email. FR-4.4 говорит о создании субъекта и контактов, не о правке.
            return subjects.upsertMerging(request.subjectData());
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
    public Consent registerImported(ConsentForm form, ImportedConsent imported) {
        Optional<Consent> existing = consents.findByIdempotencyKey(imported.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // FR-2.3 действует и на импорте: исключение сделано для архивной версии, а не для любого статуса.
        // Раньше проверка стояла только в обычной регистрации, и импорт привязывал согласие даже к черновику.
        if (form != null) {
            requireUsableForm(form, imported.source(), imported.grantedAt());
        }

        requireValidEvidence(SignatureType.IMPORTED_LEGACY, imported.evidence());

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

        consent.refreshStatus(clock.instant(), queries.expiringDays());
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
