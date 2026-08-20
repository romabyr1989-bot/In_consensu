package ru.example.inconsensu.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.catalog.domain.ApprovalDecision;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.FormApproval;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.catalog.infrastructure.ConsentFormRepository;
import ru.example.inconsensu.catalog.infrastructure.FormApprovalRepository;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.CusEvent;
import ru.example.inconsensu.common.domain.EventTypes;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.iam.application.OperatorSettingsService;

/**
 * Согласование и публикация форм (§7.2).
 *
 * <p>Состав обязательных согласующих берётся из настройки {@code inconsensu.approval.required-roles} (FR-2.1), а не
 * зашит в код: у разных операторов состав визирующих отличается.
 */
@Service
public class FormWorkflowService {

    private static final Logger LOG = LoggerFactory.getLogger(FormWorkflowService.class);

    private static final String REQUIRED_ROLES_SETTING = "inconsensu.approval.required-roles";

    private final ConsentFormRepository forms;
    private final FormApprovalRepository approvals;
    private final ConsentFormService formService;
    private final OperatorSettingsService settings;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FormWorkflowService(
            ConsentFormRepository forms,
            FormApprovalRepository approvals,
            ConsentFormService formService,
            OperatorSettingsService settings,
            AuditService auditService,
            ApplicationEventPublisher events,
            Clock clock) {
        this.forms = forms;
        this.approvals = approvals;
        this.formService = formService;
        this.settings = settings;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** FR-1.3: форма с блокирующими нарушениями не переходит в ON_REVIEW. */
    @Transactional
    public ConsentForm submit(UUID formId) {
        ConsentForm form = formService.get(formId);
        FormValidationResult validation = ru.example.inconsensu.catalog.domain.FormRequisitesValidator.validate(
                formService.validationInput(form));
        if (!validation.valid()) {
            throw ApiException.validation(
                    "Форма не соответствует обязательным реквизитам ч. 4 ст. 9 152-ФЗ",
                    validation.violations().stream()
                            .map(finding -> new ru.example.inconsensu.common.error.ValidationErrorItem(
                                    finding.itemNumber() == null ? "form" : "items[" + finding.itemNumber() + "]",
                                    finding.messageRu()))
                            .toList());
        }
        FormStatus before = form.getStatus();
        transition(form, () -> form.submitForReview(clock.instant()));

        // FR-1.4: предупреждения не блокируют, но обязаны выводиться и логироваться. В интерфейсе они
        // видны в панели конструктора, а после отправки формы след оставался бы только там — поэтому они
        // попадают и в журнал приложения, и в payload события аудита.
        List<String> warnings = validation.warnings().stream()
                .map(FormValidationResult.Finding::messageRu)
                .toList();
        if (!warnings.isEmpty()) {
            LOG.warn("Форма {} отправлена на согласование с предупреждениями: {}", form.getCode(), warnings);
        }

        Map<String, Object> payload = new LinkedHashMap<>(ConsentFormService.describe(form, before));
        payload.put("warnings", warnings);
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_SUBMITTED,
                payload);
        return forms.save(form);
    }

    @Transactional
    public ConsentForm approve(UUID formId, String comment) {
        ConsentForm form = formService.get(formId);
        if (form.getStatus() != FormStatus.ON_REVIEW) {
            throw new ApiException(ErrorCode.CONFLICT, "Одобрить можно только форму на согласовании");
        }
        Set<String> actingRoles = actingRoles(form);
        Instant now = clock.instant();
        actingRoles.forEach(role -> approvals.save(new FormApproval(
                UUID.randomUUID(),
                form,
                role,
                CurrentUser.id().orElse(null),
                CurrentUser.login(),
                ApprovalDecision.APPROVED,
                comment,
                now)));

        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_APPROVED,
                Map.of("roles", List.copyOf(actingRoles), "comment", comment == null ? "" : comment));

        if (approvedRoles(form).containsAll(requiredRoles())) {
            form.approve();
        }
        return forms.save(form);
    }

    /** FR-2.1: возврат на доработку требует обязательного комментария. */
    @Transactional
    public ConsentForm reject(UUID formId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите причину возврата формы на доработку");
        }
        ConsentForm form = formService.get(formId);
        Set<String> actingRoles = actingRoles(form);
        approvals.save(new FormApproval(
                UUID.randomUUID(),
                form,
                actingRoles.iterator().next(),
                CurrentUser.id().orElse(null),
                CurrentUser.login(),
                ApprovalDecision.REJECTED,
                comment,
                clock.instant()));
        transition(form, form::returnToDraft);
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_REJECTED,
                Map.of("roles", List.copyOf(actingRoles), "comment", comment));
        return forms.save(form);
    }

    /** FR-1.5: публикация архивирует предыдущую версию, но выданные по ней согласия остаются действующими. */
    @Transactional
    public ConsentForm publish(UUID formId) {
        ConsentForm form = formService.get(formId);
        Instant now = clock.instant();
        // FR-1.6: текст фиксируется здесь и дальше не пересобирается — реквизиты оператора изменятся,
        // а документ, под которым клиент дал согласие, обязан остаться прежним.
        String renderedText = formService.renderNow(form);
        String checksum = ru.example.inconsensu.catalog.domain.FormRenderer.checksum(renderedText);

        forms.findFirstByCodeAndStatusOrderByVersionNumberDesc(form.getCode(), FormStatus.PUBLISHED)
                .filter(previous -> !previous.getId().equals(form.getId()))
                .ifPresent(previous -> {
                    FormStatus previousBefore = previous.getStatus();
                    previous.archive(now);
                    forms.save(previous);
                    auditService.record(
                            ConsentFormService.AGGREGATE_TYPE,
                            ConsentFormService.aggregateId(previous),
                            AuditEventType.FORM_ARCHIVED,
                            ConsentFormService.describe(previous, previousBefore));
                });

        FormStatus beforePublish = form.getStatus();
        transition(form, () -> form.publish(now, renderedText, checksum));
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_PUBLISHED,
                ConsentFormService.describe(form, beforePublish));
        events.publishEvent(CusEvent.of(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                EventTypes.FORM_PUBLISHED,
                null,
                Map.of("code", form.getCode(), "version", form.getVersionNumber(), "checksum", checksum)));
        return forms.save(form);
    }

    @Transactional
    public ConsentForm archive(UUID formId) {
        ConsentForm form = formService.get(formId);
        FormStatus before = form.getStatus();
        transition(form, () -> form.archive(clock.instant()));
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_ARCHIVED,
                ConsentFormService.describe(form, before));
        return forms.save(form);
    }

    /**
     * Запись истории формы (FR-2.2): переход или решение согласующего.
     *
     * @param fromStatus статус до перехода; у решений согласующих пуст
     */
    public record HistoryEntry(
            Instant at,
            String eventType,
            String eventTypeRu,
            String fromStatus,
            String toStatus,
            String actor,
            String role,
            String decision,
            String comment) {}

    /**
     * История формы: переходы статусов из журнала аудита и решения согласующих (FR-2.2).
     *
     * <p>Раньше отдавались только одобрения и возвраты, поэтому «отправлено на согласование», «опубликовано»
     * и «в архив» в истории не появлялись вовсе, а «из какого статуса» не было видно нигде.
     */
    @Transactional(readOnly = true)
    public List<HistoryEntry> historyOf(UUID formId) {
        ConsentForm form = formService.get(formId);
        List<HistoryEntry> entries = new ArrayList<>();

        auditService
                .historyOf(ConsentFormService.AGGREGATE_TYPE, ConsentFormService.aggregateId(form))
                .forEach(event -> entries.add(new HistoryEntry(
                        event.getOccurredAt(),
                        event.getEventType().name(),
                        event.getEventType().nameRu(),
                        payloadValue(event.getPayload(), "fromStatus"),
                        payloadValue(event.getPayload(), "status"),
                        event.getActorId(),
                        null,
                        null,
                        payloadValue(event.getPayload(), "comment"))));

        approvals
                .findByFormIdOrderByDecidedAtAsc(formId)
                .forEach(approval -> entries.add(new HistoryEntry(
                        approval.getDecidedAt(),
                        approval.getDecision().name(),
                        approval.getDecision().nameRu(),
                        null,
                        null,
                        approval.getUserLogin(),
                        approval.getRoleRequired(),
                        approval.getDecision().name(),
                        approval.getComment())));

        entries.sort(java.util.Comparator.comparing(HistoryEntry::at));
        return entries;
    }

    /** Значение поля из JSON журнала без разбора всей структуры: payload — плоская карта строк и чисел. */
    private static String payloadValue(String payload, String field) {
        if (payload == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Transactional(readOnly = true)
    public List<FormApproval> approvalsOf(UUID formId) {
        return approvals.findByFormIdOrderByDecidedAtAsc(formId);
    }

    /**
     * Решения текущего круга согласования (ADR-0022): после доработки прошлые решения не засчитываются.
     *
     * <p>Панель UI-9 показывает ровно то, что учитывает переход в APPROVED, — иначе согласующий видел бы
     * одобрение, которого система уже не считает.
     */
    @Transactional(readOnly = true)
    public List<FormApproval> approvalsOfCurrentRound(UUID formId) {
        ConsentForm form = forms.findById(formId).orElseThrow(() -> ApiException.notFound("Форма не найдена"));
        Instant since = form.getSubmittedAt();
        return approvals.findByFormIdOrderByDecidedAtAsc(formId).stream()
                .filter(approval -> since == null || !approval.getDecidedAt().isBefore(since))
                .toList();
    }

    /** Роли, чьё одобрение обязательно для перехода в APPROVED (FR-2.1). */
    @Transactional(readOnly = true)
    public Set<String> requiredRoles() {
        String configured = settings.value(REQUIRED_ROLES_SETTING);
        if (configured == null || configured.isBlank()) {
            return Set.of("LAWYER", "DPO");
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Какие из обязательных ролей уже одобрили текущий круг согласования. */
    @Transactional(readOnly = true)
    public Set<String> approvedRoles(ConsentForm form) {
        Instant since = form.getSubmittedAt();
        Set<String> approved = new LinkedHashSet<>();
        for (FormApproval approval : approvals.findByFormIdOrderByDecidedAtAsc(form.getId())) {
            boolean sameRound = since == null || !approval.getDecidedAt().isBefore(since);
            if (sameRound && approval.getDecision() == ApprovalDecision.APPROVED) {
                approved.add(approval.getRoleRequired());
            }
        }
        return approved;
    }

    private Set<String> actingRoles(ConsentForm form) {
        List<String> roles = new ArrayList<>(CurrentUser.roles());
        Set<String> required = requiredRoles();
        Set<String> matching = roles.stream()
                .filter(required::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (matching.isEmpty()) {
            throw new ApiException(
                    ErrorCode.ACCESS_DENIED, "Решение по форме принимают роли: " + String.join(", ", required));
        }
        return matching;
    }

    private void transition(ConsentForm form, Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}
