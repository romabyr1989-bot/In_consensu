package ru.example.inconsensu.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Одно уведомление конкретному получателю (FR-9.1, §6).
 *
 * <p>{@code dedupeKey} уникален в базе: правило + согласие + порог. Именно он, а не проверка «было ли уже»,
 * защищает от повторной отправки при перезапуске задачи или параллельной работе двух экземпляров.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "consent_id")
    private UUID consentId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "dedupe_key", nullable = false, length = 255)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private NotificationChannel channel;

    @Column(name = "recipient", nullable = false, length = 512)
    private String recipient;

    @Column(name = "subject_line", length = 512)
    private String subjectLine;

    @Column(name = "body")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private String data = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // for JPA
    }

    public Notification(
            UUID id,
            UUID ruleId,
            UUID consentId,
            UUID subjectId,
            String dedupeKey,
            NotificationChannel channel,
            String recipient,
            String subjectLine,
            String body,
            String data,
            Instant createdAt) {
        this.id = id;
        this.ruleId = ruleId;
        this.consentId = consentId;
        this.subjectId = subjectId;
        this.dedupeKey = dedupeKey;
        this.channel = channel;
        this.recipient = recipient;
        this.subjectLine = subjectLine;
        this.body = body;
        this.data = data == null || data.isBlank() ? "{}" : data;
        this.createdAt = createdAt;
    }

    public void markSent(Instant moment) {
        this.status = NotificationStatus.SENT;
        this.sentAt = moment;
        this.attempts++;
        this.lastError = null;
    }

    /** Возврат в очередь после разбора ошибки (UI-13). */
    public void requeue() {
        this.status = NotificationStatus.PENDING;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = NotificationStatus.FAILED;
        this.attempts++;
        this.lastError = error;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public UUID getConsentId() {
        return consentId;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubjectLine() {
        return subjectLine;
    }

    public String getBody() {
        return body;
    }

    public String getData() {
        return data;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
