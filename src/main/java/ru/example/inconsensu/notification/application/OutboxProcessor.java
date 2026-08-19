package ru.example.inconsensu.notification.application;

import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.notification.domain.WebhookDelivery;

/**
 * Доставка событий из outbox в webhook-подписки (FR-9.3).
 *
 * <p>Каждое событие обрабатывается отдельно: сбой одной доставки не мешает соседним. Сам HTTP-вызов
 * выполняется между транзакциями — держать соединение с базой открытым на время сетевого таймаута нельзя.
 */
@Component
public class OutboxProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxDeliveryStore store;
    private final WebhookSender sender;

    public OutboxProcessor(OutboxDeliveryStore store, WebhookSender sender) {
        this.store = store;
        this.sender = sender;
    }

    @Scheduled(
            fixedDelayString = "${inconsensu.jobs.outbox.delay:PT30S}",
            initialDelayString = "${inconsensu.jobs.outbox.initial-delay:PT30S}")
    @SchedulerLock(name = "outboxDelivery", lockAtMostFor = "PT10M")
    public void deliver() {
        int processed = deliverNow();
        if (processed > 0) {
            LOG.info("Обработано событий outbox: {}", processed);
        }
    }

    /** Вынесено отдельно, чтобы тест и демо-сценарий не ждали планировщик. */
    public int deliverNow() {
        int processed = 0;
        for (UUID id : store.dueEventIds()) {
            processed += processOne(id) ? 1 : 0;
        }
        return processed;
    }

    private boolean processOne(UUID eventId) {
        OutboxDeliveryStore.Snapshot snapshot = store.load(eventId);
        if (snapshot == null) {
            return false;
        }
        if (snapshot.targets().isEmpty()) {
            store.skip(eventId);
            return true;
        }
        List<WebhookDelivery> results = snapshot.targets().stream()
                .map(target ->
                        sender.send(target, eventId, snapshot.eventType(), snapshot.payload(), snapshot.attempts() + 1))
                .toList();
        return store.record(eventId, results);
    }
}
