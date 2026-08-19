package ru.example.cus.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Запись транзакционного outbox (§4, §8.6). */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    /** Расписание повторов доставки (FR-9.3): 1 мин, 5 мин, 30 мин, 2 ч, 12 ч, затем FAILED. */
    public static final Duration[] RETRY_SCHEDULE = {
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(12)
    };

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(
            UUID id, String aggregateType, String aggregateId, String eventType, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void markSent(Instant moment) {
        this.status = OutboxStatus.SENT;
        this.processedAt = moment;
        this.lastError = null;
        this.attempts++;
    }

    /**
     * Откладывает следующую попытку или переводит запись в FAILED (FR-9.3).
     *
     * <p>После исчерпания расписания событие не теряется: остаётся со статусом FAILED и текстом ошибки,
     * а администратор получает уведомление.
     */
    public void markFailed(Instant moment, String error) {
        this.attempts++;
        this.lastError = error;
        if (attempts > RETRY_SCHEDULE.length) {
            this.status = OutboxStatus.FAILED;
            this.processedAt = moment;
            this.nextAttemptAt = null;
        } else {
            this.status = OutboxStatus.RETRY;
            this.nextAttemptAt = moment.plus(RETRY_SCHEDULE[attempts - 1]);
        }
    }

    /**
     * Возврат в очередь вручную (UI-14).
     *
     * <p>Счётчик попыток обнуляется: администратор чинит адрес подписки и просит систему начать заново,
     * иначе событие сразу упало бы в FAILED на первой же неудаче.
     */
    public void requeue() {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.processedAt = null;
        this.nextAttemptAt = null;
    }

    /** Нет ни одной активной подписки на такой тип: доставлять некуда, но событие остаётся в журнале. */
    public void markSkipped(Instant moment) {
        this.status = OutboxStatus.SENT;
        this.processedAt = moment;
        this.lastError = null;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
