package ru.example.inconsensu.notification.api;

import java.time.Instant;
import java.util.UUID;
import ru.example.inconsensu.notification.domain.Notification;

/** Уведомление в ответе API (FR-9.1, UI-14). */
public record NotificationResponse(
        UUID id,
        UUID ruleId,
        UUID consentId,
        String channel,
        String recipient,
        String subjectLine,
        String status,
        String statusRu,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt) {

    public static NotificationResponse of(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRuleId(),
                notification.getConsentId(),
                notification.getChannel().name(),
                notification.getRecipient(),
                notification.getSubjectLine(),
                notification.getStatus().name(),
                notification.getStatus().nameRu(),
                notification.getAttempts(),
                notification.getLastError(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
