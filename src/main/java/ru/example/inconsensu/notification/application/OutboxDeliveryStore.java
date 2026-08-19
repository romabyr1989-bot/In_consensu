package ru.example.inconsensu.notification.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.OutboxEvent;
import ru.example.inconsensu.notification.domain.OutboxStatus;
import ru.example.inconsensu.notification.domain.WebhookDelivery;
import ru.example.inconsensu.notification.domain.WebhookSubscription;
import ru.example.inconsensu.notification.infrastructure.OutboxEventRepository;
import ru.example.inconsensu.notification.infrastructure.WebhookDeliveryRepository;
import ru.example.inconsensu.notification.infrastructure.WebhookSubscriptionRepository;

/**
 * Работа с базой для доставки outbox (FR-9.3).
 *
 * <p>Отдельный бин, а не приватные методы {@link OutboxProcessor}: {@code @Transactional} действует только
 * через прокси, и вызов такого метода изнутри того же объекта прошёл бы вообще без транзакции.
 */
@Component
public class OutboxDeliveryStore {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDeliveryStore.class);
    private static final String ADMIN_ROLE = "ADMIN";

    /** Состояние события на момент выборки: HTTP-вызов выполняется уже без транзакции. */
    public record Snapshot(String eventType, String payload, int attempts, List<WebhookSubscription> targets) {}

    private final OutboxEventRepository outbox;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final NotificationService notifications;
    private final EmailSender emailSender;
    private final UserService users;
    private final Clock clock;
    private final int batchSize;

    public OutboxDeliveryStore(
            OutboxEventRepository outbox,
            WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries,
            NotificationService notifications,
            EmailSender emailSender,
            UserService users,
            Clock clock,
            InConsensuProperties properties) {
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.users = users;
        this.clock = clock;
        this.batchSize = properties.notifications().batchSize();
    }

    /** Очередь на доставку без тех событий, у которых по тому же агрегату есть более раннее недоставленное. */
    @Transactional(readOnly = true)
    public List<UUID> dueEventIds() {
        return outbox.findDue(clock.instant(), PageRequest.of(0, batchSize)).stream()
                .filter(event -> outbox.countEarlierUnsent(
                                event.getAggregateType(), event.getAggregateId(), event.getCreatedAt())
                        == 0)
                .map(OutboxEvent::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public Snapshot load(UUID eventId) {
        return outbox.findById(eventId)
                .filter(event -> event.getStatus() == OutboxStatus.PENDING || event.getStatus() == OutboxStatus.RETRY)
                .map(event -> new Snapshot(
                        event.getEventType(),
                        event.getPayload(),
                        event.getAttempts(),
                        subscriptions.findByActiveTrueOrderByNameAsc().stream()
                                .filter(subscription -> subscription.accepts(event.getEventType()))
                                .toList()))
                .orElse(null);
    }

    /** Подписчиков нет — доставлять некуда, но событие остаётся в журнале как обработанное. */
    @Transactional
    public void skip(UUID eventId) {
        outbox.findById(eventId).ifPresent(event -> {
            event.markSkipped(clock.instant());
            outbox.save(event);
        });
    }

    @Transactional
    public boolean record(UUID eventId, List<WebhookDelivery> results) {
        OutboxEvent event = outbox.findById(eventId).orElse(null);
        if (event == null) {
            return false;
        }
        deliveries.saveAll(results);
        List<WebhookDelivery> failed =
                results.stream().filter(delivery -> !delivery.isSuccessful()).toList();
        if (failed.isEmpty()) {
            event.markSent(clock.instant());
        } else {
            event.markFailed(clock.instant(), failed.get(0).getError());
        }
        outbox.save(event);
        if (event.getStatus() == OutboxStatus.FAILED) {
            notifyAdmins(event);
        }
        return true;
    }

    /** FR-9.3: исчерпанное расписание повторов — инцидент, о котором обязан узнать администратор. */
    private void notifyAdmins(OutboxEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", event.getEventType());
        data.put("deliveryId", event.getId().toString());
        data.put("attempts", event.getAttempts());
        data.put("lastError", event.getLastError());
        String body = emailSender.render("delivery-failed", data);

        Set<String> admins = users.emailsByRoles(Set.of(ADMIN_ROLE));
        for (String admin : admins) {
            notifications.enqueue(
                    null,
                    null,
                    null,
                    "delivery-failed:" + event.getId() + ":" + admin,
                    NotificationChannel.EMAIL,
                    admin,
                    "In consensu: событие не доставлено",
                    body,
                    data);
        }
        LOG.warn("Событие {} не доставлено после {} попыток", event.getId(), event.getAttempts());
    }
}
