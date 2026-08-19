package ru.example.inconsensu.notification.api;

import java.util.List;
import java.util.UUID;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;

/** Правило уведомления в ответе API (FR-9.1, UI-14). */
public record NotificationRuleResponse(
        UUID id,
        String name,
        String triggerType,
        String triggerTypeRu,
        List<Integer> daysBefore,
        UUID consentTypeId,
        UUID thirdPartyId,
        List<String> recipientEmails,
        List<String> recipientRoles,
        List<String> channels,
        boolean active) {

    public static NotificationRuleResponse of(NotificationRule rule) {
        return new NotificationRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getTriggerType().name(),
                rule.getTriggerType().nameRu(),
                rule.getDaysBefore(),
                rule.getConsentTypeId(),
                rule.getThirdPartyId(),
                List.copyOf(rule.getRecipientEmails()),
                List.copyOf(rule.getRecipientRoles()),
                rule.getChannels().stream().map(NotificationChannel::name).toList(),
                rule.isActive());
    }
}
