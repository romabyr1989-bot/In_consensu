package ru.example.inconsensu.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.notification.domain.WebhookDelivery;
import ru.example.inconsensu.notification.domain.WebhookSubscription;
import ru.example.inconsensu.notification.domain.WebhookUrlPolicy;
import ru.example.inconsensu.notification.infrastructure.WebhookDeliveryRepository;
import ru.example.inconsensu.notification.infrastructure.WebhookSubscriptionRepository;

/** Подписки на события и тестовая отправка (FR-9.4, FR-9.5, UI-15). */
@Service
public class WebhookSubscriptionService {

    public static final String AGGREGATE_TYPE = "webhook_subscription";

    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param headers дополнительные заголовки подписки; секрет задаётся отдельно и наружу не отдаётся */
    public record SubscriptionForm(
            String name, String url, Set<String> eventTypes, Map<String, String> headers, boolean active) {}

    private final ru.example.inconsensu.common.config.InConsensuProperties.Webhook webhook;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSender sender;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookSubscriptionService(
            ru.example.inconsensu.common.config.InConsensuProperties properties,
            WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries,
            WebhookSender sender,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.webhook = properties.notifications().webhook();
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.sender = sender;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> list() {
        return subscriptions.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public WebhookSubscription get(UUID id) {
        return subscriptions.findById(id).orElseThrow(() -> ApiException.notFound("Подписка не найдена"));
    }

    /** Последняя доставка подписки и её результат (UI-14). */
    @Transactional(readOnly = true)
    public java.util.Optional<WebhookDelivery> lastDeliveryOf(UUID subscriptionId) {
        return deliveries.findFirstBySubscriptionIdOrderByDeliveredAtDesc(subscriptionId);
    }

    @Transactional(readOnly = true)
    public Page<WebhookDelivery> deliveriesOf(UUID subscriptionId, Pageable pageable) {
        get(subscriptionId);
        return deliveries.findBySubscriptionIdOrderByDeliveredAtDesc(subscriptionId, pageable);
    }

    /**
     * Создаёт подписку и возвращает её вместе с секретом.
     *
     * <p>Секрет показывается один раз, при создании и при ротации: хранить его в открытом виде в ответах
     * API незачем, а потребителю он нужен, чтобы проверять подпись (FR-9.3).
     */
    @Transactional
    public Created create(SubscriptionForm form) {
        validate(form);
        String secret = generateSecret();
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(), form.name(), form.url(), secret);
        subscription.update(form.name(), form.url(), form.eventTypes(), toJson(form.headers()), form.active());
        WebhookSubscription saved = subscriptions.save(subscription);
        audit(saved, AuditEventType.CREATED);
        return new Created(saved, secret);
    }

    @Transactional
    public WebhookSubscription update(UUID id, SubscriptionForm form) {
        validate(form);
        WebhookSubscription subscription = get(id);
        subscription.update(form.name(), form.url(), form.eventTypes(), toJson(form.headers()), form.active());
        WebhookSubscription saved = subscriptions.save(subscription);
        audit(saved, AuditEventType.UPDATED);
        return saved;
    }

    @Transactional
    public Created rotateSecret(UUID id) {
        WebhookSubscription subscription = get(id);
        String secret = generateSecret();
        subscription.rotateSecret(secret);
        WebhookSubscription saved = subscriptions.save(subscription);
        // Сам секрет в журнал не попадает (NFR-3) — только факт ротации.
        audit(saved, AuditEventType.UPDATED);
        return new Created(saved, secret);
    }

    @Transactional
    public WebhookSubscription deactivate(UUID id) {
        WebhookSubscription subscription = get(id);
        subscription.update(
                subscription.getName(),
                subscription.getUrl(),
                subscription.getEventTypes(),
                subscription.getHeaders(),
                false);
        WebhookSubscription saved = subscriptions.save(subscription);
        audit(saved, AuditEventType.DEACTIVATED);
        return saved;
    }

    /**
     * Тестовая отправка (FR-9.5): проверяет адрес, заголовки и подпись, не создавая события в outbox.
     *
     * <p>Запись в журнале доставок появляется настоящая: она и есть доказательство того, что интеграция
     * настроена, — а событие с типом {@code test.ping} потребитель обязан игнорировать.
     */
    @Transactional
    public WebhookDelivery sendTest(UUID id) {
        WebhookSubscription subscription = get(id);
        UUID deliveryId = UUID.randomUUID();
        String body = toJson(Map.of(
                "event", "test.ping", "occurredAt", clock.instant().toString(), "deliveryId", deliveryId.toString()));
        WebhookDelivery delivery = sender.send(subscription, deliveryId, "test.ping", body, 1);
        return deliveries.save(delivery);
    }

    private void validate(SubscriptionForm form) {
        if (form.name() == null || form.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите название подписки");
        }
        // NFR-4: адрес проверяется по списку разрешённых хостов — иначе роль ADMIN превращается в
        // возможность заставить сервис ходить в произвольный, в том числе внутренний, адрес.
        WebhookUrlPolicy.check(form.url(), webhook.allowedHosts(), webhook.requireHttps());
    }

    private void audit(WebhookSubscription subscription, AuditEventType eventType) {
        auditService.record(
                AGGREGATE_TYPE,
                subscription.getId().toString(),
                eventType,
                Map.of(
                        "name", subscription.getName(),
                        "url", subscription.getUrl(),
                        "active", subscription.isActive(),
                        "eventTypes", subscription.getEventTypes()));
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Не удалось разобрать заголовки подписки");
        }
    }

    /** @param secret показывается один раз: при создании подписки и при ротации секрета */
    public record Created(WebhookSubscription subscription, String secret) {}
}
