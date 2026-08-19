package ru.example.cus.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Журнал попыток доставки webhook: что, куда, когда и с каким ответом (FR-9.4, §10). */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    /** Ответ сервера не хранится целиком: в теле ошибки может оказаться что угодно, включая ПДн (NFR-3). */
    private static final int ERROR_MAX_LENGTH = 500;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "outbox_event_id", nullable = false)
    private UUID outboxEventId;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "error")
    private String error;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    protected WebhookDelivery() {
        // for JPA
    }

    public WebhookDelivery(
            UUID id,
            UUID subscriptionId,
            UUID outboxEventId,
            int attempt,
            Integer responseCode,
            String error,
            Instant deliveredAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.outboxEventId = outboxEventId;
        this.attempt = attempt;
        this.responseCode = responseCode;
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX_LENGTH));
        this.deliveredAt = deliveredAt;
    }

    public boolean isSuccessful() {
        return responseCode != null && responseCode >= 200 && responseCode < 300;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getOutboxEventId() {
        return outboxEventId;
    }

    public int getAttempt() {
        return attempt;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public String getError() {
        return error;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
