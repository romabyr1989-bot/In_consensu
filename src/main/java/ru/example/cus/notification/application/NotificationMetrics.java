package ru.example.cus.notification.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.notification.domain.NotificationStatus;
import ru.example.cus.notification.domain.OutboxStatus;
import ru.example.cus.notification.infrastructure.NotificationRepository;
import ru.example.cus.notification.infrastructure.OutboxEventRepository;

/**
 * Метрики очереди событий и уведомлений (NFR-6).
 *
 * <p>Длина очереди и число недоставленных событий — то, по чему дежурный понимает, что интеграции встали,
 * раньше, чем об этом сообщит потребитель.
 */
@Component
public class NotificationMetrics implements InitializingBean {

    private final OutboxEventRepository outbox;
    private final NotificationRepository notifications;
    private final MeterRegistry registry;

    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxRetry = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong notificationsFailed = new AtomicLong();

    public NotificationMetrics(
            OutboxEventRepository outbox, NotificationRepository notifications, MeterRegistry registry) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        Gauge.builder("cus.outbox.queue", outboxPending, AtomicLong::get)
                .description("События, ожидающие первой отправки")
                .register(registry);
        Gauge.builder("cus.outbox.retry", outboxRetry, AtomicLong::get)
                .description("События, ожидающие повтора доставки")
                .register(registry);
        Gauge.builder("cus.outbox.failed", outboxFailed, AtomicLong::get)
                .description("События, не доставленные после исчерпания расписания повторов")
                .register(registry);
        Gauge.builder("cus.notifications.failed", notificationsFailed, AtomicLong::get)
                .description("Уведомления, которые не удалось отправить")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${cus.metrics.refresh:PT1M}", initialDelayString = "${cus.metrics.refresh:PT1M}")
    @Transactional(readOnly = true)
    public void refresh() {
        outboxPending.set(outbox.countByStatus(OutboxStatus.PENDING));
        outboxRetry.set(outbox.countByStatus(OutboxStatus.RETRY));
        outboxFailed.set(outbox.countByStatus(OutboxStatus.FAILED));
        notificationsFailed.set(notifications.countByStatus(NotificationStatus.FAILED));
    }
}
