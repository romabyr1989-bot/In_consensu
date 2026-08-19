package ru.example.inconsensu.notification.api;

import java.time.Instant;
import java.util.UUID;
import ru.example.inconsensu.notification.domain.WebhookDelivery;

/** Попытка доставки в ответе API (FR-9.5). */
public record WebhookDeliveryResponse(
        UUID id,
        UUID subscriptionId,
        UUID deliveryId,
        int attempt,
        Integer responseCode,
        String error,
        boolean successful,
        Instant deliveredAt) {

    public static WebhookDeliveryResponse of(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(
                delivery.getId(),
                delivery.getSubscriptionId(),
                delivery.getOutboxEventId(),
                delivery.getAttempt(),
                delivery.getResponseCode(),
                delivery.getError(),
                delivery.isSuccessful(),
                delivery.getDeliveredAt());
    }
}
