package ru.example.cus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.ConsentFormItem;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.notification.application.OutboxProcessor;
import ru.example.cus.notification.application.WebhookSender;
import ru.example.cus.notification.application.WebhookSubscriptionService;
import ru.example.cus.notification.domain.OutboxEvent;
import ru.example.cus.notification.domain.OutboxStatus;
import ru.example.cus.notification.domain.WebhookSignature;
import ru.example.cus.notification.infrastructure.OutboxEventRepository;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.RevocationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;
import ru.example.cus.support.TestForms;
import ru.example.cus.support.WebhookStub;

/** Приёмка этапа 6: событие из транзакции доходит до подписчика с подписью и в правильном порядке (FR-9.3, FR-9.4). */
class WebhookDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private RevocationService revocation;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private TestForms testForms;

    /**
     * База в тестах общая, и подписки соседних сценариев указывают на уже закрытые порты. Их нужно
     * погасить, иначе каждое событие уходит в неизбежный повтор и проверки становятся случайными.
     */
    @BeforeEach
    void deactivateForeignSubscriptions() {
        RunAs.rolesVoid("test-admin", List.of("ADMIN"), () -> subscriptions.list().stream()
                .filter(subscription -> subscription.isActive())
                .forEach(subscription -> subscriptions.deactivate(subscription.getId())));
        drainOutbox();
    }

    /** Очередь общая для всей сессии: чтобы дойти до своих событий, пакет нужно вычерпать до конца. */
    private void drainOutbox() {
        for (int pass = 0; pass < 50 && processor.deliverNow() > 0; pass++) {
            // очередь опустошается пакетами
        }
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem item = form.getItems().stream()
                .filter(candidate -> candidate.getConsentType().getCode().equals("PDN_PROCESSING"))
                .findFirst()
                .orElseThrow();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Чкалов",
                "Пётр",
                "Иванович",
                null,
                List.of(new SubjectService.ContactForm(ContactType.EMAIL, "chkalov@example.ru", true)));

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
                                "заявка вебхука",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000042",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }

    @Test
    void granted_and_revoked_events_reach_the_subscriber_in_order_and_are_signed() {
        try (WebhookStub stub = new WebhookStub(200)) {
            var created = RunAs.roles(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                            "CRM " + UUID.randomUUID().toString().substring(0, 8),
                            stub.url(),
                            Set.of(EventTypes.CONSENT_GRANTED, EventTypes.CONSENT_REVOKED),
                            Map.of("X-Tenant", "cus"),
                            true)));

            Consent consent = registerConsent();
            revocation.revoke(
                    consent.getId(),
                    "передумал",
                    RevocationSource.PERSONAL_ACCOUNT,
                    "ОБР-" + UUID.randomUUID(),
                    Map.of());

            drainOutbox();

            List<WebhookStub.Received> received = stub.received().stream()
                    .filter(request -> request.body().contains(consent.getId().toString()))
                    .toList();
            assertThat(received).hasSizeGreaterThanOrEqualTo(2);

            // FR-9.3: порядок событий по одному согласию сохраняется — «выдано» не может прийти после «отозвано».
            List<String> events = received.stream()
                    .map(request -> request.headers().get("x-cus-event"))
                    .toList();
            assertThat(events.indexOf(EventTypes.CONSENT_GRANTED))
                    .isLessThan(events.indexOf(EventTypes.CONSENT_REVOKED));

            WebhookStub.Received first = received.get(0);
            assertThat(first.headers()).containsKey("x-cus-delivery-id");
            assertThat(first.headers()).containsEntry("x-tenant", "cus");
            assertThat(first.headers().get("x-cus-signature"))
                    .isEqualTo(WebhookSignature.sign(created.secret(), first.body()));

            // NFR-3: наружу уходит только внешний идентификатор субъекта, без ФИО, контактов и доказательств.
            assertThat(first.body()).contains("\"externalId\"").doesNotContain("Чкалов");
            String revoked = received.stream()
                    .filter(request ->
                            EventTypes.CONSENT_REVOKED.equals(request.headers().get("x-cus-event")))
                    .findFirst()
                    .orElseThrow()
                    .body();
            assertThat(revoked).contains("processingStopDeadline");
            assertThat(revoked)
                    .doesNotContain("evidence")
                    .doesNotContain("+7916")
                    .doesNotContain("10.0.0.1");
        }
    }

    @Test
    void event_without_active_subscription_is_marked_processed_not_lost() {
        Consent consent = registerConsent();
        drainOutbox();

        List<OutboxEvent> events = outbox.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "consent", consent.getId().toString());
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    void failed_delivery_is_retried_on_schedule_and_journaled() {
        try (WebhookStub stub = new WebhookStub(503)) {
            RunAs.rolesVoid(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                            "Недоступный " + UUID.randomUUID().toString().substring(0, 8),
                            stub.url(),
                            Set.of(EventTypes.CONSENT_GRANTED),
                            Map.of(),
                            true)));

            Consent consent = registerConsent();
            drainOutbox();

            OutboxEvent event = outbox.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                            "consent", consent.getId().toString())
                    .get(0);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.RETRY);
            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getNextAttemptAt()).isAfter(event.getCreatedAt());
            assertThat(event.getLastError()).contains("503");
        }
    }

    @Test
    void test_send_reaches_the_subscriber_without_creating_an_event() {
        try (WebhookStub stub = new WebhookStub(204)) {
            var created = RunAs.roles(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                            "Проверка " + UUID.randomUUID().toString().substring(0, 8),
                            stub.url(),
                            Set.of(),
                            Map.of(),
                            true)));

            var delivery = RunAs.roles(
                    "test-admin",
                    List.of("ADMIN"),
                    () -> subscriptions.sendTest(created.subscription().getId()));

            assertThat(delivery.isSuccessful()).isTrue();
            assertThat(delivery.getResponseCode()).isEqualTo(204);
            assertThat(stub.received()).hasSize(1);
            assertThat(stub.received().get(0).headers()).containsEntry("x-cus-event", "test.ping");
            assertThat(stub.received().get(0).headers())
                    .containsKey(WebhookSender.DELIVERY_HEADER.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
