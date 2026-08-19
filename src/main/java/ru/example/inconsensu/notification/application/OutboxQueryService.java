package ru.example.inconsensu.notification.application;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.notification.domain.OutboxEvent;
import ru.example.inconsensu.notification.domain.OutboxStatus;
import ru.example.inconsensu.notification.domain.WebhookDelivery;
import ru.example.inconsensu.notification.infrastructure.OutboxEventRepository;
import ru.example.inconsensu.notification.infrastructure.WebhookDeliveryRepository;

/** Чтение очереди событий и журнала доставок: дашборд UI-2 и экран UI-14. */
@Service
public class OutboxQueryService {

    private final OutboxEventRepository outbox;
    private final WebhookDeliveryRepository deliveries;

    public OutboxQueryService(OutboxEventRepository outbox, WebhookDeliveryRepository deliveries) {
        this.outbox = outbox;
        this.deliveries = deliveries;
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> failed(int limit) {
        return outbox.findByStatusOrderByCreatedAtDesc(OutboxStatus.FAILED, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Page<OutboxEvent> list(Pageable pageable) {
        return outbox.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveriesOfEvent(UUID eventId) {
        return deliveries.findByOutboxEventIdOrderByAttemptAsc(eventId);
    }

    /**
     * Повторная отправка события вручную (UI-14).
     *
     * <p>Событие возвращается в очередь, а не отправляется здесь же: доставкой занимается обработчик
     * outbox, и второй путь отправки означал бы второй набор ошибок.
     */
    @Transactional
    public OutboxEvent retry(UUID eventId) {
        OutboxEvent event = outbox.findById(eventId)
                .orElseThrow(() -> ru.example.inconsensu.common.error.ApiException.notFound("Событие не найдено"));
        event.requeue();
        return outbox.save(event);
    }
}
