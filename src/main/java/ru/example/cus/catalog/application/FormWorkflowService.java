package ru.example.cus.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.catalog.domain.ApprovalDecision;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.FormApproval;
import ru.example.cus.catalog.domain.FormValidationResult;
import ru.example.cus.catalog.infrastructure.ConsentFormRepository;
import ru.example.cus.catalog.infrastructure.FormApprovalRepository;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.CusEvent;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.common.domain.FormStatus;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.common.security.CurrentUser;
import ru.example.cus.iam.application.OperatorSettingsService;

/**
 * Согласование и публикация форм (§7.2).
 *
 * <p>Состав обязательных согласующих берётся из настройки {@code cus.approval.required-roles} (FR-2.1), а не
 * зашит в код: у разных операторов состав визирующих отличается.
 */
@Service
public class FormWorkflowService {

    private static final String REQUIRED_ROLES_SETTING = "cus.approval.required-roles";

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
        FormValidationResult validation =
                ru.example.cus.catalog.domain.FormRequisitesValidator.validate(formService.validationInput(form));
        if (!validation.valid()) {
            throw ApiException.validation(
                    "Форма не соответствует обязательным реквизитам ч. 4 ст. 9 152-ФЗ",
                    validation.violations().stream()
                            .map(finding -> new ru.example.cus.common.error.ValidationErrorItem(
                                    finding.itemNumber() == null ? "form" : "items[" + finding.itemNumber() + "]",
                                    finding.messageRu()))
                            .toList());
        }
        transition(form, () -> form.submitForReview(clock.instant()));
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_SUBMITTED,
                ConsentFormService.describe(form));
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
        String checksum = formService.checksumOf(form);

        forms.findFirstByCodeAndStatusOrderByVersionNumberDesc(form.getCode(), FormStatus.PUBLISHED)
                .filter(previous -> !previous.getId().equals(form.getId()))
                .ifPresent(previous -> {
                    previous.archive(now);
                    forms.save(previous);
                    auditService.record(
                            ConsentFormService.AGGREGATE_TYPE,
                            ConsentFormService.aggregateId(previous),
                            AuditEventType.FORM_ARCHIVED,
                            ConsentFormService.describe(previous));
                });

        transition(form, () -> form.publish(now, checksum));
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_PUBLISHED,
                ConsentFormService.describe(form));
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
        transition(form, () -> form.archive(clock.instant()));
        auditService.record(
                ConsentFormService.AGGREGATE_TYPE,
                ConsentFormService.aggregateId(form),
                AuditEventType.FORM_ARCHIVED,
                ConsentFormService.describe(form));
        return forms.save(form);
    }

    @Transactional(readOnly = true)
    public List<FormApproval> historyOf(UUID formId) {
        return approvals.findByFormIdOrderByDecidedAtAsc(formId);
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
