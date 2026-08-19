package ru.example.inconsensu.notification.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.notification.infrastructure.NotificationRuleRepository;

/** Правила уведомлений (FR-9.1, FR-9.2, UI-14). */
@Service
public class NotificationRuleService {

    public static final String AGGREGATE_TYPE = "notification_rule";

    public record RuleForm(
            String name,
            NotificationTrigger triggerType,
            List<Integer> daysBefore,
            UUID consentTypeId,
            UUID thirdPartyId,
            Set<String> recipientEmails,
            Set<String> recipientRoles,
            Set<NotificationChannel> channels,
            boolean active) {}

    private final NotificationRuleRepository rules;
    private final AuditService auditService;

    public NotificationRuleService(NotificationRuleRepository rules, AuditService auditService) {
        this.rules = rules;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<NotificationRule> list() {
        return rules.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public NotificationRule get(UUID id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("Правило уведомления не найдено"));
    }

    @Transactional
    public NotificationRule create(RuleForm form) {
        validate(form);
        NotificationRule rule = new NotificationRule(UUID.randomUUID(), form.name(), form.triggerType());
        apply(rule, form);
        NotificationRule saved = rules.save(rule);
        audit(saved, AuditEventType.CREATED);
        return saved;
    }

    @Transactional
    public NotificationRule update(UUID id, RuleForm form) {
        validate(form);
        NotificationRule rule = get(id);
        apply(rule, form);
        NotificationRule saved = rules.save(rule);
        audit(saved, AuditEventType.UPDATED);
        return saved;
    }

    @Transactional
    public NotificationRule deactivate(UUID id) {
        NotificationRule rule = get(id);
        rule.update(
                rule.getName(),
                rule.getTriggerType(),
                rule.getDaysBefore(),
                rule.getConsentTypeId(),
                rule.getThirdPartyId(),
                rule.getRecipientEmails(),
                rule.getRecipientRoles(),
                rule.getChannels(),
                false);
        NotificationRule saved = rules.save(rule);
        audit(saved, AuditEventType.DEACTIVATED);
        return saved;
    }

    private void apply(NotificationRule rule, RuleForm form) {
        rule.update(
                form.name(),
                form.triggerType(),
                form.daysBefore(),
                form.consentTypeId(),
                form.thirdPartyId(),
                form.recipientEmails(),
                form.recipientRoles(),
                form.channels(),
                form.active());
    }

    private void validate(RuleForm form) {
        if (form.name() == null || form.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите название правила");
        }
        if (form.triggerType() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите повод для уведомления");
        }
        if (form.channels() == null || form.channels().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите хотя бы один канал доставки");
        }
        boolean noRecipients = (form.recipientEmails() == null
                        || form.recipientEmails().isEmpty())
                && (form.recipientRoles() == null || form.recipientRoles().isEmpty());
        if (form.channels().contains(NotificationChannel.EMAIL) && noRecipients) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Для канала «письмо» укажите адреса или роли получателей");
        }
        if (form.triggerType() != NotificationTrigger.EXPIRED
                && (form.daysBefore() == null || form.daysBefore().isEmpty())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите хотя бы один порог в днях");
        }
        if (form.daysBefore() != null && form.daysBefore().stream().anyMatch(days -> days == null || days < 0)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Порог в днях не может быть отрицательным");
        }
    }

    private void audit(NotificationRule rule, AuditEventType eventType) {
        auditService.record(
                AGGREGATE_TYPE,
                rule.getId().toString(),
                eventType,
                Map.of(
                        "name", rule.getName(),
                        "trigger", rule.getTriggerType().name(),
                        "daysBefore", rule.getDaysBefore(),
                        "channels", rule.getChannels(),
                        "active", rule.isActive()));
    }
}
