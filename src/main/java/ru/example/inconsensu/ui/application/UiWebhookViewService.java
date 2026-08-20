package ru.example.inconsensu.ui.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.notification.application.WebhookSubscriptionService;
import ru.example.inconsensu.notification.domain.WebhookDelivery;

/**
 * Модель экранов подписок на события (UI-14).
 *
 * <p>Список подписок не показывал последнюю доставку и её результат — администратор не видел, работает
 * ли подписка, пока не открывал журнал каждой по отдельности.
 */
@Service
public class UiWebhookViewService {

    /** @param lastDeliveryResult «код 200», «ошибка …» или «доставок не было» */
    public record SubscriptionRow(
            UUID id,
            String name,
            String url,
            Set<String> eventTypes,
            boolean active,
            String lastDeliveryAt,
            String lastDeliveryResult,
            boolean lastDeliverySuccessful) {}

    /** @param eventId идентификатор события outbox: по нему делается повторная доставка */
    public record DeliveryRow(
            UUID eventId, String deliveredAt, int attempt, String responseCode, String error, boolean successful) {}

    private final WebhookSubscriptionService subscriptions;
    private final UiFormats formats;

    public UiWebhookViewService(WebhookSubscriptionService subscriptions, UiFormats formats) {
        this.subscriptions = subscriptions;
        this.formats = formats;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionRow> subscriptions() {
        return subscriptions.list().stream()
                .map(subscription -> {
                    WebhookDelivery last =
                            subscriptions.lastDeliveryOf(subscription.getId()).orElse(null);
                    return new SubscriptionRow(
                            subscription.getId(),
                            subscription.getName(),
                            subscription.getUrl(),
                            subscription.getEventTypes(),
                            subscription.isActive(),
                            last == null ? "" : formats.dateTime(last.getDeliveredAt()),
                            resultOf(last),
                            last != null && last.isSuccessful());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<DeliveryRow> deliveries(UUID subscriptionId, Pageable pageable) {
        return subscriptions
                .deliveriesOf(subscriptionId, pageable)
                .map(delivery -> new DeliveryRow(
                        delivery.getOutboxEventId(),
                        formats.dateTime(delivery.getDeliveredAt()),
                        delivery.getAttempt(),
                        delivery.getResponseCode() == null ? "нет ответа" : String.valueOf(delivery.getResponseCode()),
                        delivery.getError(),
                        delivery.isSuccessful()));
    }

    private static String resultOf(WebhookDelivery delivery) {
        if (delivery == null) {
            return "доставок не было";
        }
        if (delivery.isSuccessful()) {
            return "код " + delivery.getResponseCode();
        }
        return delivery.getError() == null || delivery.getError().isBlank()
                ? "нет ответа"
                : "ошибка: " + delivery.getError();
    }
}
