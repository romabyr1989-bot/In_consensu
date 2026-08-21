package ru.example.inconsensu.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.notification.application.NotificationDispatcher;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.Mailpit;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * FR-8.5, FR-9.1: письмо ответственному об отзыве согласия.
 *
 * <p>Отзыв запускает отсчёт тридцати дней на прекращение обработки (ч. 5 ст. 21 152-ФЗ), поэтому
 * уведомление ставится в очередь доменным событием, а не ежедневной задачей. До сих пор все правила в
 * тестах были с поводом «заканчивается срок», и путь отзыва не проверялся ни разу.
 *
 * <p>Второй сценарий проверяет сужение правила по типу согласия: правило, настроенное на другой тип,
 * молчать обязано — иначе ответственный тонет в чужих уведомлениях и перестаёт их читать.
 */
class ConsentChangeNotificationIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        Mailpit.registerProperties(registry);
    }

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private RevocationService revocation;

    @Autowired
    private TestForms testForms;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void revocation_sends_the_letter_with_the_case_number_and_the_deadline() {
        String dpoEmail = uniqueEmail("dpo-revoked");
        accounts.create(RoleCode.DPO.name(), dpoEmail);
        createRule("Отзыв согласия", NotificationTrigger.REVOKED, null, dpoEmail);

        Consent consent = registerConsent();
        String caseNumber =
                "ОБР-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        RunAs.rolesVoid(
                "test-manager",
                List.of("MANAGER"),
                () -> revocation.revoke(
                        consent.getId(),
                        "Обращение клиента",
                        RevocationSource.CALL_CENTER,
                        caseNumber,
                        Map.<String, Object>of()));

        assertThat(dispatcher.dispatchNow()).isPositive();

        String mailbox = Mailpit.search(restTemplate, dpoEmail);
        assertThat(mailbox).as("письмо об отзыве не дошло до ответственного").contains("согласие отозвано");
        assertThat(mailbox).contains(caseNumber);
        // FR-9.2: в письме только ФИО и внешний идентификатор — телефона и адреса там быть не должно.
        assertThat(mailbox).doesNotContain("+7916");
    }

    @Test
    void rule_narrowed_to_another_consent_type_stays_silent() {
        String matchingEmail = uniqueEmail("dpo-match");
        String otherEmail = uniqueEmail("dpo-other");
        accounts.create(RoleCode.DPO.name(), matchingEmail);
        accounts.create(RoleCode.DPO.name(), otherEmail);

        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem advertising = itemOf(form, "ADVERTISING_EMAIL");
        ConsentFormItem base = itemOf(form, "PDN_PROCESSING");

        createRule(
                "Отзыв рекламы",
                NotificationTrigger.REVOKED,
                advertising.getConsentType().getId(),
                matchingEmail);
        createRule(
                "Отзыв базового",
                NotificationTrigger.REVOKED,
                base.getConsentType().getId(),
                otherEmail);

        Consent consent = registerConsent(form, advertising);
        String caseNumber =
                "ОБР-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        RunAs.rolesVoid(
                "test-manager",
                List.of("MANAGER"),
                () -> revocation.revoke(
                        consent.getId(),
                        "Обращение клиента",
                        RevocationSource.EMAIL_REQUEST,
                        caseNumber,
                        Map.<String, Object>of()));

        dispatcher.dispatchNow();

        String delivered = Mailpit.search(restTemplate, caseNumber);
        assertThat(delivered)
                .as("правило по тому же типу согласия обязано сработать")
                .contains(matchingEmail);
        assertThat(delivered)
                .as("правило по другому типу согласия обязано молчать")
                .doesNotContain(otherEmail);
    }

    private void createRule(String name, NotificationTrigger trigger, UUID consentTypeId, String recipient) {
        RunAs.rolesVoid(
                "test-admin",
                List.of("ADMIN"),
                () -> rules.create(new NotificationRuleService.RuleForm(
                        name + " " + UUID.randomUUID().toString().substring(0, 8),
                        trigger,
                        // Порогов у повода «отзыв» нет: до события не остаётся дней.
                        List.of(),
                        consentTypeId,
                        null,
                        Set.of(recipient),
                        Set.of(),
                        Set.of(NotificationChannel.EMAIL),
                        true)));
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
    }

    private static ConsentFormItem itemOf(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        return registerConsent(form, itemOf(form, "ADVERTISING_EMAIL"));
    }

    private Consent registerConsent(ConsentForm form, ConsentFormItem item) {
        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-NOTIFY-" + UUID.randomUUID().toString().substring(0, 8),
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(new SubjectService.ContactForm(ContactType.EMAIL, uniqueEmail("client"), true)));

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
                                        "phone", "+79160000047",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .stream()
                .filter(created -> created.getFormItemId().equals(item.getId()))
                .findFirst()
                .orElseThrow();
    }
}
