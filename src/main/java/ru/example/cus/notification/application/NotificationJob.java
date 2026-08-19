package ru.example.cus.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.domain.CusEvent;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.notification.domain.ExpiringConsentPort;
import ru.example.cus.notification.domain.ExpiringContractPort;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationRule;
import ru.example.cus.notification.domain.NotificationTrigger;
import ru.example.cus.notification.infrastructure.NotificationRuleRepository;

/**
 * Ежедневная задача уведомлений о переподписании (FR-9.1).
 *
 * <p>Пороги обрабатываются по одному и независимо: «за 30 дней» и «за 7 дней» — два разных уведомления с
 * разными ключами дедупликации, поэтому пропуск одного запуска не превращается в тишину до самого конца
 * срока, а повторный запуск не рассылает письма дважды.
 */
@Component
public class NotificationJob {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationJob.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Результат прогона — для теста, ручного запуска и отчёта в журнале. */
    public record ScanResult(int expiring, int expired, int contracts) {

        public int total() {
            return expiring + expired + contracts;
        }
    }

    private final NotificationRuleRepository rules;
    private final NotificationService notifications;
    private final ExpiringConsentPort consents;
    private final ExpiringContractPort contracts;
    private final EmailSender emailSender;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;
    private final Clock clock;

    public NotificationJob(
            NotificationRuleRepository rules,
            NotificationService notifications,
            ExpiringConsentPort consents,
            ExpiringContractPort contracts,
            EmailSender emailSender,
            ApplicationEventPublisher events,
            Clock clock) {
        this.rules = rules;
        this.notifications = notifications;
        this.consents = consents;
        this.contracts = contracts;
        this.emailSender = emailSender;
        this.events = events;
        this.zone = clock.getZone();
        this.clock = clock;
    }

    @Scheduled(cron = "${cus.jobs.notification.cron:0 0 6 * * *}", zone = "${cus.timezone:Europe/Moscow}")
    @SchedulerLock(name = "notificationScan", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scan() {
        ScanResult result = scanNow();
        LOG.info(
                "Уведомления: заканчивается {}, истекло {}, договоры {}",
                result.expiring(),
                result.expired(),
                result.contracts());
    }

    /** Вынесено отдельно, чтобы тест и ручной запуск не ждали cron. */
    @Transactional
    public ScanResult scanNow() {
        int expiring = 0;
        for (NotificationRule rule : rules.findByActiveTrueAndTriggerTypeOrderByNameAsc(NotificationTrigger.EXPIRING)) {
            for (Integer threshold : rule.getDaysBefore()) {
                for (ExpiringConsentPort.ExpiringConsent consent :
                        consents.findExpiringIn(threshold, rule.getConsentTypeId())) {
                    expiring += notify(rule, consent, threshold, EventTypes.CONSENT_EXPIRING);
                }
            }
        }

        int expired = 0;
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(1));
        for (NotificationRule rule : rules.findByActiveTrueAndTriggerTypeOrderByNameAsc(NotificationTrigger.EXPIRED)) {
            for (ExpiringConsentPort.ExpiringConsent consent :
                    consents.findExpiredBetween(from, to, rule.getConsentTypeId())) {
                expired += notify(rule, consent, 0, EventTypes.CONSENT_EXPIRED);
            }
        }

        int contractsNotified = 0;
        for (NotificationRule rule :
                rules.findByActiveTrueAndTriggerTypeOrderByNameAsc(NotificationTrigger.THIRD_PARTY_CONTRACT_EXPIRING)) {
            for (Integer threshold : rule.getDaysBefore()) {
                for (ExpiringContractPort.ExpiringContract contract : contracts.findContractsExpiringIn(threshold)) {
                    contractsNotified += notifyContract(rule, contract, threshold);
                }
            }
        }
        return new ScanResult(expiring, expired, contractsNotified);
    }

    private int notify(
            NotificationRule rule, ExpiringConsentPort.ExpiringConsent consent, int threshold, String eventType) {
        String base = rule.getId() + ":" + consent.consentId() + ":" + threshold;
        int created = 0;

        if (rule.hasChannel(NotificationChannel.WEBHOOK)
                && notifications.enqueueWebhook(
                        rule.getId(),
                        consent.consentId(),
                        consent.subjectId(),
                        base + ":webhook",
                        rule.getName(),
                        consentData(consent, threshold))) {
            events.publishEvent(CusEvent.of(
                    "consent",
                    consent.consentId().toString(),
                    eventType,
                    consent.subjectId(),
                    Map.of("typeName", consent.consentTypeName(), "daysBefore", threshold)));
            created++;
        }

        if (!rule.hasChannel(NotificationChannel.EMAIL)) {
            return created;
        }
        Map<String, Object> data = consentData(consent, threshold);
        Set<String> recipients = notifications.recipientsOf(rule);
        String subjectLine = EventTypes.CONSENT_EXPIRED.equals(eventType)
                ? "ЦУС: истёк срок согласия"
                : "ЦУС: заканчивается срок согласия";
        String body = emailSender.render(
                EventTypes.CONSENT_EXPIRED.equals(eventType) ? "consent-expired" : "consent-expiring", data);

        for (String recipient : recipients) {
            if (notifications.enqueue(
                    rule.getId(),
                    consent.consentId(),
                    consent.subjectId(),
                    base + ":" + recipient,
                    NotificationChannel.EMAIL,
                    recipient,
                    subjectLine,
                    body,
                    data)) {
                created++;
            }
        }
        return created;
    }

    private int notifyContract(NotificationRule rule, ExpiringContractPort.ExpiringContract contract, int threshold) {
        String base = rule.getId() + ":" + contract.thirdPartyId() + ":" + threshold;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thirdPartyName", contract.name());
        data.put("contractNumber", contract.contractNumber());
        data.put(
                "validUntil",
                contract.contractValidUntil() == null
                        ? ""
                        : contract.contractValidUntil().format(DATE));
        data.put("daysBefore", threshold);
        data.put("thirdPartyId", contract.thirdPartyId().toString());

        int created = 0;
        if (rule.hasChannel(NotificationChannel.WEBHOOK)
                && notifications.enqueueWebhook(rule.getId(), null, null, base + ":webhook", rule.getName(), data)) {
            events.publishEvent(CusEvent.of(
                    "third_party",
                    contract.thirdPartyId().toString(),
                    EventTypes.THIRD_PARTY_CONTRACT_EXPIRING,
                    null,
                    Map.of("name", contract.name(), "daysBefore", threshold)));
            created++;
        }
        if (!rule.hasChannel(NotificationChannel.EMAIL)) {
            return created;
        }

        String body = emailSender.render("contract-expiring", data);
        for (String recipient : notifications.recipientsOf(rule)) {
            if (notifications.enqueue(
                    rule.getId(),
                    null,
                    null,
                    base + ":" + recipient,
                    NotificationChannel.EMAIL,
                    recipient,
                    "ЦУС: заканчивается договор с третьим лицом",
                    body,
                    data)) {
                created++;
            }
        }
        return created;
    }

    /** Состав данных ограничен FR-9.2: ФИО и внешний идентификатор, без иных ПДн. */
    private Map<String, Object> consentData(ExpiringConsentPort.ExpiringConsent consent, int threshold) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectFullName", consent.subjectFullName());
        data.put("subjectExternalId", consent.subjectExternalId());
        data.put("consentTypeName", consent.consentTypeName());
        data.put("thirdPartyName", consent.thirdPartyName() == null ? "" : consent.thirdPartyName());
        data.put(
                "validUntil",
                consent.validUntil() == null
                        ? ""
                        : LocalDate.ofInstant(consent.validUntil(), zone).format(DATE));
        data.put("daysBefore", threshold);
        data.put("consentId", consent.consentId().toString());
        data.put("subjectId", subjectIdOf(consent));
        return data;
    }

    private static String subjectIdOf(ExpiringConsentPort.ExpiringConsent consent) {
        UUID subjectId = consent.subjectId();
        return subjectId == null ? "" : subjectId.toString();
    }

    /** Список правил для ручного запуска и тестов. */
    @Transactional(readOnly = true)
    public List<NotificationRule> activeRules(NotificationTrigger trigger) {
        return rules.findByActiveTrueAndTriggerTypeOrderByNameAsc(trigger);
    }
}
