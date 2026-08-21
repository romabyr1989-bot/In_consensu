package ru.example.inconsensu.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.notification.application.NotificationDispatcher;
import ru.example.inconsensu.notification.application.NotificationJob;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.Mailpit;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestForms;

/**
 * FR-9.2: дайджест собирается по всей очереди правила, а не по одной порции.
 *
 * <p>Решение «дайджест или отдельные письма» принималось по той пачке, которую диспетчер взял за проход.
 * Если за день по правилу набиралось больше уведомлений, чем размер порции, ответственный получал и сводку,
 * и отдельные письма — ровно то, от чего дайджест должен избавлять.
 *
 * <p>Порция здесь занижена свойствами: контекст с иными настройками Spring поднимает отдельно, поэтому
 * маленький размер пачки не влияет на остальные тесты.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inconsensu.notifications.batch-size=2")
class NotificationDigestBatchIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        Mailpit.registerProperties(registry);
    }

    private static final int NOTIFICATIONS = 3;

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationJob job;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private OperatorSettingsService settings;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ConsentRepository consents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private TestForms testForms;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Autowired
    private ru.example.inconsensu.notification.application.NotificationService notifications;

    @Test
    void the_whole_queue_of_a_rule_becomes_one_digest_even_if_it_exceeds_the_batch() {
        // Адрес указывается правилу явно и НЕ принадлежит пользователю: правила соседних тестов рассылают
        // по ролям, и учётная запись DPO с этим адресом получала бы ещё и их письма.
        String dpoEmail = "batch-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";

        String previousThreshold = settings.value(NotificationDispatcher.DIGEST_THRESHOLD_SETTING);
        RunAs.rolesVoid(
                "test-admin",
                List.of("ADMIN"),
                () -> settings.update(Map.of(NotificationDispatcher.DIGEST_THRESHOLD_SETTING, "1")));
        try {
            var rule = RunAs.roles(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> rules.create(new NotificationRuleService.RuleForm(
                            "Порция " + UUID.randomUUID().toString().substring(0, 8),
                            NotificationTrigger.EXPIRING,
                            List.of(25),
                            null,
                            null,
                            Set.of(dpoEmail),
                            Set.of(),
                            Set.of(NotificationChannel.EMAIL),
                            true)));

            Instant validUntil = Instant.now().plus(Duration.ofDays(25)).truncatedTo(ChronoUnit.HOURS);
            for (int index = 0; index < NOTIFICATIONS; index++) {
                Consent consent = registerConsent();
                transactions.executeWithoutResult(status -> consents.updateValidUntil(consent.getId(), validUntil));
            }
            assertThat(job.scanNow().expiring()).isGreaterThanOrEqualTo(NOTIFICATIONS);

            // Проходов может понадобиться несколько: порция мала, и в неё попадают уведомления соседних
            // тестов. Важно другое — когда очередь этого правила доходит до диспетчера, она уходит целиком.
            for (int pass = 0; pass < 20 && notifications.pendingCount(rule.getId(), dpoEmail) > 0; pass++) {
                dispatcher.dispatchNow();
            }

            assertThat(Mailpit.count(dpoEmail))
                    .as("получателю обязано уйти одно письмо-дайджест, а не сводка плюс остаток письмами")
                    .isEqualTo(1);
            assertThat(Mailpit.search(restTemplate, dpoEmail)).contains("Уведомлений за сегодня");
            // Главное: в очереди правила ничего не осталось. Раньше «хвост» за границей порции ждал
            // следующего прохода и уходил отдельным письмом.
            assertThat(notifications.pendingCount(rule.getId(), dpoEmail))
                    .as("очередь правила обязана уйти в один дайджест целиком")
                    .isZero();
        } finally {
            RunAs.rolesVoid(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> settings.update(Map.of(NotificationDispatcher.DIGEST_THRESHOLD_SETTING, previousThreshold)));
        }
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem item = form.getItems().stream()
                .filter(candidate -> candidate.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-BATCH-" + UUID.randomUUID().toString().substring(0, 8),
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(new SubjectService.ContactForm(
                        ContactType.EMAIL,
                        "batch-client-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                        true)));

        return registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                subject,
                                form.getId(),
                                List.of(new ConsentRegistrationService.ItemDecision(item.getId(), true)),
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "порция дайджеста",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000049",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }
}
