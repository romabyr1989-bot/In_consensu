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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
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
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * Приёмка этапа 6: письмо о переподписании реально доходит до почтового сервера (FR-9.1, FR-9.2).
 *
 * <p>Проверяется через Mailpit, а не через мок {@code JavaMailSender}: мок подтвердил бы только вызов метода,
 * тогда как здесь письмо действительно уходит по SMTP и читается обратно из ящика.
 */
class NotificationEmailIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        ru.example.inconsensu.support.Mailpit.registerProperties(registry);
    }

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ConsentRepository consents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationJob job;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private TestForms testForms;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private OperatorSettingsService settings;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ru.example.inconsensu.notification.application.NotificationService notifications;

    @Test
    void expiring_consent_produces_an_email_to_the_dpo() {
        String dpoEmail = "dpo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
        accounts.create(RoleCode.DPO.name(), dpoEmail);

        var rule = RunAs.roles(
                "test-admin",
                List.of("ADMIN"),
                () -> rules.create(new NotificationRuleService.RuleForm(
                        "Переподписание " + UUID.randomUUID().toString().substring(0, 8),
                        NotificationTrigger.EXPIRING,
                        List.of(30),
                        null,
                        null,
                        Set.of(),
                        Set.of(RoleCode.DPO.name()),
                        Set.of(NotificationChannel.EMAIL),
                        true)));

        Consent consent = registerConsent();
        // Срок подводится ровно к порогу правила: задача отбирает согласия по календарному дню оператора.
        Instant validUntil = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS);
        transactions.executeWithoutResult(status -> consents.updateValidUntil(consent.getId(), validUntil));

        NotificationJob.ScanResult scan = job.scanNow();
        assertThat(scan.expiring()).isPositive();

        dispatchUntilSent(rule.getId(), dpoEmail);

        String mailbox = mailpitSearch(dpoEmail);
        assertThat(mailbox).contains("заканчивается срок согласия");
        assertThat(mailbox).contains(dpoEmail);
    }

    @Test
    void a_large_batch_for_one_recipient_collapses_into_one_digest_with_csv() {
        String dpoEmail = "digest-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
        accounts.create(RoleCode.DPO.name(), dpoEmail);

        // Порог дайджеста опускается до одного уведомления: смысл проверки — переход от пачки писем к сводке.
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
                            "Сводка " + UUID.randomUUID().toString().substring(0, 8),
                            NotificationTrigger.EXPIRING,
                            List.of(20),
                            null,
                            null,
                            Set.of(),
                            Set.of(RoleCode.DPO.name()),
                            Set.of(NotificationChannel.EMAIL),
                            true)));

            Instant validUntil = Instant.now().plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.HOURS);
            for (int i = 0; i < 3; i++) {
                Consent consent = registerConsent();
                transactions.executeWithoutResult(status -> consents.updateValidUntil(consent.getId(), validUntil));
            }

            assertThat(job.scanNow().expiring()).isGreaterThanOrEqualTo(3);
            dispatchUntilSent(rule.getId(), dpoEmail);

            String mailbox = mailpitSearch(dpoEmail);
            assertThat(mailbox).contains("Уведомлений за сегодня");
            assertThat(mailbox).contains("notifications.csv");
            assertThat(rule.getName()).isNotBlank();
        } finally {
            RunAs.rolesVoid(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> settings.update(Map.of(NotificationDispatcher.DIGEST_THRESHOLD_SETTING, previousThreshold)));
        }
    }

    /**
     * FR-9.2: дайджест собирается из уведомлений с разным набором полей.
     *
     * <p>У письма об отзыве нет срока действия, у письма об истечении он есть. Thymeleaf на отсутствующем
     * ключе карты не возвращает null, а бросает исключение, поэтому одно «чужое» уведомление срывало всю
     * сводку целиком. Дефект нашёлся только в CI: локально порядок классов не сводил их в одну группу.
     */
    @Test
    void digest_survives_notifications_with_different_sets_of_fields() {
        String recipient = "mixed-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
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
                            "Смешанная сводка " + UUID.randomUUID().toString().substring(0, 8),
                            NotificationTrigger.EXPIRING,
                            List.of(20),
                            null,
                            null,
                            Set.of(recipient),
                            Set.of(),
                            Set.of(NotificationChannel.EMAIL),
                            true)));
            UUID ruleId = rule.getId();
            notifications.enqueue(
                    ruleId,
                    null,
                    null,
                    "mixed:full:" + recipient,
                    NotificationChannel.EMAIL,
                    recipient,
                    "In consensu: заканчивается срок согласия",
                    "<html><body>полное</body></html>",
                    Map.of(
                            "subjectFullName", "Полевая Мария Ивановна",
                            "subjectExternalId", "CRM-MIX-1",
                            "consentTypeName", "Реклама по email",
                            "thirdPartyName", "",
                            "validUntil", "01.09.2026"));
            // Набор полей письма об отзыве: срока действия в нём нет.
            notifications.enqueue(
                    ruleId,
                    null,
                    null,
                    "mixed:revoked:" + recipient,
                    NotificationChannel.EMAIL,
                    recipient,
                    "In consensu: согласие отозвано",
                    "<html><body>отзыв</body></html>",
                    Map.of("subjectFullName", "Полевая Мария Ивановна", "subjectExternalId", "CRM-MIX-1"));

            dispatchUntilSent(ruleId, recipient);

            String mailbox = mailpitSearch(recipient);
            assertThat(mailbox).contains("Уведомлений за сегодня");
            assertThat(mailbox).contains("notifications.csv");
        } finally {
            RunAs.rolesVoid(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> settings.update(Map.of(NotificationDispatcher.DIGEST_THRESHOLD_SETTING, previousThreshold)));
        }
    }

    /**
     * Прогоняет диспетчер, пока очередь правила по этому адресату не опустеет.
     *
     * <p>За проход берётся ограниченная порция самых старых уведомлений, и в неё попадают письма соседних
     * тестов: один вызов мог отправить чужое, а проверяемое оставить в очереди. Тест падал через раз и
     * только в CI, где порядок классов другой.
     */
    private void dispatchUntilSent(UUID ruleId, String recipient) {
        for (int pass = 0; pass < 20 && notifications.pendingCount(ruleId, recipient) > 0; pass++) {
            dispatcher.dispatchNow();
        }
    }

    private String mailpitSearch(String query) {
        return ru.example.inconsensu.support.Mailpit.search(restTemplate, query);
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem item = form.getItems().stream()
                .filter(candidate -> candidate.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(new SubjectService.ContactForm(ContactType.EMAIL, "bondarenko@example.ru", true)));

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
                                "заявка уведомления",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000043",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }
}
