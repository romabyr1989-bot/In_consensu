package ru.example.cus.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.example.cus.notification.domain.WebhookSubscription;

/**
 * Подписка на события в ответе API (FR-9.4, UI-15).
 *
 * <p>Секрет подписи наружу не отдаётся: он показывается один раз при создании и при ротации (NFR-3).
 */
public record WebhookSubscriptionResponse(
        UUID id,
        String name,
        String url,
        List<String> eventTypes,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static WebhookSubscriptionResponse of(WebhookSubscription subscription) {
        return new WebhookSubscriptionResponse(
                subscription.getId(),
                subscription.getName(),
                subscription.getUrl(),
                List.copyOf(subscription.getEventTypes()),
                subscription.isActive(),
                subscription.getCreatedAt(),
                subscription.getUpdatedAt());
    }
}
