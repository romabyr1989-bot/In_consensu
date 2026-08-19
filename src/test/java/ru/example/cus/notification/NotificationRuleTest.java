package ru.example.cus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.notification.domain.Notification;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationRule;
import ru.example.cus.notification.domain.NotificationStatus;
import ru.example.cus.notification.domain.NotificationTrigger;

/** FR-9.1, FR-9.2: правило без типа согласия действует на все типы; каналы перечисляются явно. */
class NotificationRuleTest {

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-08-18T06:00:00Z");

    private static NotificationRule rule() {
        return new NotificationRule(UUID.randomUUID(), "Истекающие согласия", NotificationTrigger.EXPIRING);
    }

    @Test
    void ruleWithoutTypeAppliesToEveryType() {
        assertThat(rule().appliesToType(UUID.randomUUID())).isTrue();
    }

    @Test
    void ruleWithTypeAppliesOnlyToIt() {
        UUID typeId = UUID.randomUUID();
        NotificationRule rule = rule();
        rule.update(
                "Реклама",
                NotificationTrigger.EXPIRING,
                List.of(30),
                typeId,
                null,
                Set.of("dpo@example.ru"),
                Set.of(),
                Set.of(NotificationChannel.EMAIL),
                true);

        assertThat(rule.appliesToType(typeId)).isTrue();
        assertThat(rule.appliesToType(UUID.randomUUID())).isFalse();
        assertThat(rule.hasChannel(NotificationChannel.EMAIL)).isTrue();
        assertThat(rule.hasChannel(NotificationChannel.WEBHOOK)).isFalse();
        assertThat(rule.getDaysBefore()).containsExactly(30);
    }

    @Test
    void notificationTracksDeliveryOutcome() {
        Notification notification = new Notification(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "rule:consent:30:dpo@example.ru",
                NotificationChannel.EMAIL,
                "dpo@example.ru",
                "ЦУС: заканчивается срок согласия",
                "<html></html>",
                "{}",
                NOW);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);

        notification.markFailed("Connection refused");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);

        notification.markSent(NOW.plusSeconds(30));
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getSentAt()).isEqualTo(NOW.plusSeconds(30));
    }
}
