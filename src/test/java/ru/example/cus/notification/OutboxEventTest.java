package ru.example.cus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.notification.domain.OutboxEvent;
import ru.example.cus.notification.domain.OutboxStatus;

/** FR-9.3: расписание повторов 1 мин, 5 мин, 30 мин, 2 ч, 12 ч, затем FAILED. */
class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    private static OutboxEvent event() {
        return new OutboxEvent(
                UUID.randomUUID(), "consent", UUID.randomUUID().toString(), "consent.revoked", "{}", NOW);
    }

    @Test
    void newEventIsDueImmediately() {
        OutboxEvent event = event();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void retryScheduleFollowsRequirement() {
        OutboxEvent event = event();
        Duration[] expected = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12)
        };
        for (Duration delay : expected) {
            event.markFailed(NOW, "HTTP 503");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.RETRY);
            assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(delay));
        }
    }

    @Test
    void exhaustedScheduleEndsInFailed() {
        OutboxEvent event = event();
        for (int attempt = 0; attempt <= OutboxEvent.RETRY_SCHEDULE.length; attempt++) {
            event.markFailed(NOW, "HTTP 503");
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("HTTP 503");
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void successAfterFailuresClearsError() {
        OutboxEvent event = event();
        event.markFailed(NOW, "timeout");
        event.markSent(NOW.plusSeconds(60));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getAttempts()).isEqualTo(2);
    }

    @Test
    void skippedEventCountsAsProcessedWithoutAttempt() {
        OutboxEvent event = event();
        event.markSkipped(NOW);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getAttempts()).isZero();
    }
}
