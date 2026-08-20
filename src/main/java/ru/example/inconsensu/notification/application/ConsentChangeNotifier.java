package ru.example.inconsensu.notification.application;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.CusEvent;
import ru.example.inconsensu.common.domain.EventTypes;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationSubjectPort;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.notification.infrastructure.NotificationRuleRepository;

/**
 * Письма по поводам «получено согласие» и «согласие отозвано» (FR-8.5, FR-9.1, §6).
 *
 * <p>Отзыв — событие, а не состояние, которое можно найти ежедневной задачей: ответственный обязан узнать
 * о нём сразу, потому что ч. 5 ст. 21 152-ФЗ отсчитывает тридцать дней на прекращение обработки от
 * момента отзыва. Поэтому уведомления ставятся в очередь по доменному событию.
 *
 * <p>Слушатель синхронный и работает в транзакции издателя, как и запись в outbox: уведомление обязано
 * появиться вместе с самим отзывом.
 */
@Component
public class ConsentChangeNotifier {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final NotificationRuleRepository rules;
    private final NotificationService notifications;
    private final NotificationSubjectPort subjects;
    private final EmailSender emailSender;
    private final ZoneId zone;
    private final Clock clock;

    public ConsentChangeNotifier(
            NotificationRuleRepository rules,
            NotificationService notifications,
            NotificationSubjectPort subjects,
            EmailSender emailSender,
            InConsensuProperties properties,
            Clock clock) {
        this.rules = rules;
        this.notifications = notifications;
        this.subjects = subjects;
        this.emailSender = emailSender;
        this.zone = properties.timezone();
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(CusEvent event) {
        NotificationTrigger trigger = triggerOf(event.eventType());
        if (trigger == null || event.subjectId() == null) {
            return;
        }
        for (NotificationRule rule : rules.findByActiveTrueAndTriggerTypeOrderByNameAsc(trigger)) {
            if (!matches(rule, event) || !rule.hasChannel(NotificationChannel.EMAIL)) {
                continue;
            }
            notifyBy(rule, event, trigger);
        }
    }

    private static NotificationTrigger triggerOf(String eventType) {
        if (EventTypes.CONSENT_REVOKED.equals(eventType)) {
            return NotificationTrigger.REVOKED;
        }
        return EventTypes.CONSENT_GRANTED.equals(eventType) ? NotificationTrigger.GRANTED : null;
    }

    /** Правило может быть сужено до типа согласия или до третьего лица (FR-9.1). */
    private static boolean matches(NotificationRule rule, CusEvent event) {
        return matchesId(rule.getConsentTypeId(), event.payload().get("consentTypeId"))
                && matchesId(rule.getThirdPartyId(), event.payload().get("thirdPartyId"));
    }

    private static boolean matchesId(UUID expected, Object actual) {
        return expected == null || Objects.toString(actual, "").equals(expected.toString());
    }

    private void notifyBy(NotificationRule rule, CusEvent event, NotificationTrigger trigger) {
        Map<String, Object> data = mailData(event, trigger);
        String template = trigger == NotificationTrigger.REVOKED ? "consent-revoked" : "consent-granted";
        String subjectLine = trigger == NotificationTrigger.REVOKED
                ? "In consensu: согласие отозвано"
                : "In consensu: получено согласие";
        String body = emailSender.render(template, data);
        String base = rule.getId() + ":" + event.aggregateId() + ":" + trigger.name();

        Set<String> recipients = notifications.recipientsOf(rule);
        for (String recipient : recipients) {
            notifications.enqueue(
                    rule.getId(),
                    UUID.fromString(event.aggregateId()),
                    event.subjectId(),
                    base + ":" + recipient,
                    NotificationChannel.EMAIL,
                    recipient,
                    subjectLine,
                    body,
                    data);
        }
    }

    /** В письмо попадают только ФИО и внешний идентификатор — иных ПДн быть не должно (FR-9.2). */
    private Map<String, Object> mailData(CusEvent event, NotificationTrigger trigger) {
        Map<String, Object> data = new LinkedHashMap<>();
        subjects.find(event.subjectId()).ifPresent(subject -> {
            data.put("subjectFullName", subject.fullName());
            data.put("subjectExternalId", subject.externalId());
        });
        data.put("subjectId", event.subjectId().toString());
        data.put("consentTypeName", Objects.toString(event.payload().get("typeName"), ""));
        data.put("thirdPartyName", Objects.toString(event.payload().get("thirdPartyName"), ""));
        data.put("caseNumber", Objects.toString(event.payload().get("caseNumber"), ""));
        data.put("revokedAt", DATE_TIME.format(clock.instant().atZone(zone)));
        Object deadline = event.payload().get("processingStopDeadline");
        data.put(
                "processingStopDeadline",
                deadline == null
                        ? ""
                        : DATE.format(
                                java.time.Instant.parse(deadline.toString()).atZone(zone)));
        return data;
    }
}
