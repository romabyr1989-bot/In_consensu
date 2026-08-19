package ru.example.cus.notification;

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
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.ConsentFormItem;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.iam.application.OperatorSettingsService;
import ru.example.cus.notification.application.NotificationDispatcher;
import ru.example.cus.notification.application.NotificationJob;
import ru.example.cus.notification.application.NotificationRuleService;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationTrigger;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.infrastructure.ConsentRepository;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;
import ru.example.cus.support.TestAccounts;
import ru.example.cus.support.TestForms;

/**
 * Приёмка этапа 6: письмо о переподписании реально доходит до почтового сервера (FR-9.1, FR-9.2).
 *
 * <p>Проверяется через Mailpit, а не через мок {@code JavaMailSender}: мок подтвердил бы только вызов метода,
 * тогда как здесь письмо действительно уходит по SMTP и читается обратно из ящика.
 */
class NotificationEmailIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        ru.example.cus.support.Mailpit.registerProperties(registry);
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

        int dispatched = dispatcher.dispatchNow();
        assertThat(dispatched).isPositive();

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
            assertThat(dispatcher.dispatchNow()).isGreaterThanOrEqualTo(3);

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

    private String mailpitSearch(String query) {
        return ru.example.cus.support.Mailpit.search(restTemplate, query);
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
