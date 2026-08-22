package ru.example.inconsensu;

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
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.channels.application.ChannelService;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.EventTypes;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.notification.application.NotificationDispatcher;
import ru.example.inconsensu.notification.application.NotificationJob;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.OutboxProcessor;
import ru.example.inconsensu.notification.application.WebhookSubscriptionService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.Mailpit;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.WebhookStub;

/**
 * Обязательный сквозной сценарий §11 одним тестом.
 *
 * <p>Форма → согласование → публикация → регистрация согласия → карточка → канал разрешён → отзыв →
 * канал запрещён → событие в outbox → webhook доставлен → письмо об истечении получено в Mailpit.
 *
 * <p>Отдельные шаги покрыты своими тестами; здесь проверяется, что цепочка работает целиком: именно на
 * стыках модулей ломается то, что по отдельности исправно.
 */
class EndToEndScenarioIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        Mailpit.registerProperties(registry);
    }

    @Autowired
    private ConsentFormService forms;

    @Autowired
    private FormWorkflowService workflow;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private SubjectCardService cards;

    @Autowired
    private ChannelService channels;

    @Autowired
    private RevocationService revocation;

    @Autowired
    private OutboxProcessor outbox;

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationJob notificationJob;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private ConsentRepository consents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private ru.example.inconsensu.iam.application.OperatorSettingsService settings;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * База в тестах общая, и подписки соседних сценариев указывают на уже закрытые порты. Пока их события
     * не доставлены, порядок по агрегату (FR-9.3) придержит и наши — поэтому чужие подписки гасятся, а
     * хвост очереди вычерпывается до начала сценария.
     */
    @org.junit.jupiter.api.BeforeEach
    void isolateOutbox() {
        RunAs.rolesVoid("e2e-admin", List.of("ADMIN"), () -> subscriptions.list().stream()
                .filter(subscription -> subscription.isActive())
                .forEach(subscription -> subscriptions.deactivate(subscription.getId())));
        drainOutbox();
    }

    @Test
    void the_whole_chain_from_a_draft_form_to_the_expiry_email_works() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dpoEmail = "dpo-e2e-" + suffix + "@example.ru";
        accounts.create(RoleCode.DPO.name(), dpoEmail);

        try (WebhookStub consumer = new WebhookStub(200)) {
            var subscription = RunAs.roles(
                    "e2e-admin",
                    List.of("ADMIN"),
                    () -> subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                            "Потребитель " + suffix,
                            consumer.url(),
                            Set.of(EventTypes.CONSENT_GRANTED, EventTypes.CONSENT_REVOKED),
                            Map.of(),
                            true)));

            // 1. Форма: черновик → согласование юристом и DPO → публикация.
            ConsentForm form = publishForm(suffix);
            assertThat(form.getStatus()).isEqualTo(FormStatus.PUBLISHED);
            assertThat(form.getRenderedChecksum()).startsWith("sha256:");

            // 2. Регистрация согласий по опубликованной форме.
            Consent advertising = registerConsents(form, suffix);
            UUID subjectId = advertising.getSubjectId();

            // 3. Карточка и канал: реклама по email разрешена.
            var card = RunAs.roles("e2e-manager", List.of("MANAGER"), () -> cards.cardOf(subjectId));
            assertThat(card.consents()).hasSize(2);
            assertThat(allowed(subjectId, CommunicationChannel.EMAIL))
                    .as("до отзыва рекламный канал должен быть разрешён")
                    .isTrue();

            // 4. Отзыв рекламного согласия.
            RunAs.rolesVoid(
                    "e2e-manager",
                    List.of("MANAGER"),
                    () -> revocation.revoke(
                            advertising.getId(),
                            "клиент попросил прекратить рассылку",
                            RevocationSource.CALL_CENTER,
                            "ОБР-E2E-" + suffix,
                            Map.of()));

            // 5. Канал закрывается немедленно, без ожидания фоновых задач (FR-6.3).
            assertThat(allowed(subjectId, CommunicationChannel.EMAIL))
                    .as("после отзыва рекламный канал обязан закрыться")
                    .isFalse();

            // 6. События ушли в outbox и доставлены подписчику с подписью.
            drainOutbox();
            List<WebhookStub.Received> delivered = consumer.received().stream()
                    .filter(request ->
                            request.body().contains(advertising.getId().toString()))
                    .toList();
            assertThat(delivered)
                    .as("подписчик должен получить события по этому согласию")
                    .isNotEmpty();
            assertThat(delivered)
                    .anySatisfy(request -> assertThat(request.headers().get("x-inconsensu-event"))
                            .isEqualTo(EventTypes.CONSENT_REVOKED));
            assertThat(delivered.get(0).headers()).containsKey("x-inconsensu-signature");

            // 7. Письмо о переподписании доходит до DPO.
            expiryEmailReaches(dpoEmail, suffix, subjectId);
        }
    }

    private ConsentForm publishForm(String suffix) {
        settings.update(Map.of(
                "operator.name", "ООО «Тестовый оператор»",
                "operator.address", "123001, Москва, ул. Тестовая, д. 1"));

        String code = "E2E_" + suffix.toUpperCase();
        ConsentForm draft = RunAs.roles(
                "e2e-lawyer",
                List.of("LAWYER"),
                () -> forms.createDraft(
                        code,
                        new ConsentFormService.FormDraft(
                                "Сквозной сценарий",
                                "Я, {{subject.fio}}, телефон {{subject.phone}}, email {{subject.email}}, даю согласие "
                                        + "{{operator.name}} ({{operator.address}}) на обработку персональных данных.",
                                "сбор, запись, хранение, уничтожение",
                                "действует до отзыва; отзыв — в личном кабинете",
                                Set.of(ConsentSource.WEBSITE_APPLICATION),
                                List.of(
                                        new ConsentFormService.ItemForm(
                                                "PDN_PROCESSING",
                                                "Согласие на обработку персональных данных",
                                                List.of("рассмотрение заявки"),
                                                List.of("FIO", "PHONE", "EMAIL"),
                                                null,
                                                null,
                                                true),
                                        new ConsentFormService.ItemForm(
                                                "ADVERTISING_EMAIL",
                                                "Согласие на рекламу по электронной почте",
                                                List.of("информирование о продуктах"),
                                                List.of("EMAIL"),
                                                null,
                                                "P1Y",
                                                false)))));

        RunAs.rolesVoid("e2e-lawyer", List.of("LAWYER"), () -> workflow.submit(draft.getId()));
        RunAs.rolesVoid("e2e-lawyer", List.of("LAWYER"), () -> workflow.approve(draft.getId(), "проверено юристом"));
        RunAs.rolesVoid("e2e-dpo", List.of("DPO"), () -> workflow.approve(draft.getId(), "проверено DPO"));
        return RunAs.roles("e2e-dpo", List.of("DPO"), () -> workflow.publish(draft.getId()));
    }

    private Consent registerConsents(ConsentForm form, String suffix) {
        UUID advertisingItemId = form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow()
                .getId();

        var result = RunAs.roles(
                "e2e-integration",
                List.of("INTEGRATION"),
                () -> registration.register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                new SubjectService.SubjectForm(
                                        "CRM-E2E-" + suffix,
                                        "Травин",
                                        "Иван",
                                        "Сергеевич",
                                        null,
                                        List.of(
                                                new SubjectService.ContactForm(
                                                        ContactType.PHONE, "+7 916 000-02-11", true),
                                                new SubjectService.ContactForm(
                                                        ContactType.EMAIL, "e2e-" + suffix + "@example.ru", true))),
                                form.getId(),
                                form.getItems().stream()
                                        .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                                        .toList(),
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "заявка сквозного сценария",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000211",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla"))));

        return result.created().stream()
                .filter(consent -> advertisingItemId.equals(consent.getFormItemId()))
                .findFirst()
                .orElseThrow();
    }

    private void expiryEmailReaches(String dpoEmail, String suffix, UUID subjectId) {
        RunAs.rolesVoid(
                "e2e-admin",
                List.of("ADMIN"),
                () -> rules.create(new NotificationRuleService.RuleForm(
                        "Переподписание " + suffix,
                        NotificationTrigger.EXPIRING,
                        List.of(30),
                        null,
                        null,
                        Set.of(),
                        Set.of(RoleCode.DPO.name()),
                        Set.of(NotificationChannel.EMAIL),
                        true)));

        // Базовое согласие бессрочное, поэтому срок подводится к порогу правила вручную.
        Instant validUntil = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS);
        UUID baseConsentId =
                RunAs.roles("e2e-admin", List.of("ADMIN"), () -> cards.cardOf(subjectId).consents().stream()
                        .map(view -> view.consent().getId())
                        .findFirst()
                        .orElseThrow());
        transactions.executeWithoutResult(status -> consents.updateValidUntil(baseConsentId, validUntil));

        assertThat(RunAs.roles("e2e-admin", List.of("ADMIN"), () -> notificationJob
                        .scanNow()
                        .expiring()))
                .as("задача обязана заметить согласие с порогом 30 дней")
                .isPositive();
        // Порция диспетчера ограничена, и в неё могли попасть уведомления соседних тестов: проходов делается
        // столько, сколько нужно, чтобы очередь дошла до нашего адресата. Раньше один проход мог отправить
        // чужие письма, а этот сценарий оставался без своего.
        String mailbox = "";
        for (int pass = 0; pass < 10 && !mailbox.contains("заканчивается срок согласия"); pass++) {
            dispatcher.dispatchNow();
            mailbox = Mailpit.search(restTemplate, dpoEmail);
        }
        assertThat(mailbox).contains("заканчивается срок согласия");
    }

    private boolean allowed(UUID subjectId, CommunicationChannel channel) {
        return RunAs.roles("e2e-manager", List.of("MANAGER"), () -> channels.channelsOf(subjectId)).decisions().stream()
                .filter(decision -> decision.channel() == channel)
                .findFirst()
                .orElseThrow()
                .allowed();
    }

    /** Очередь общая для сессии тестов: до своих событий нужно вычерпать хвост чужих. */
    private void drainOutbox() {
        for (int pass = 0; pass < 50 && outbox.deliverNow() > 0; pass++) {
            // очередь опустошается пакетами
        }
    }
}
