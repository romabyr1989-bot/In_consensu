package ru.example.inconsensu.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.domain.CusEvent;
import ru.example.inconsensu.notification.domain.NotificationSubjectPort;
import ru.example.inconsensu.notification.domain.OutboxEvent;
import ru.example.inconsensu.notification.infrastructure.OutboxEventRepository;

/**
 * Складывает доменные события в outbox (§8.6).
 *
 * <p>Слушатель намеренно синхронный и без {@code @Async}: он обязан работать в транзакции издателя, чтобы
 * запись в outbox фиксировалась вместе с изменением данных. {@code @TransactionalEventListener(AFTER_COMMIT)}
 * здесь был бы ошибкой — событие потерялось бы при падении между коммитом и записью.
 *
 * <p>Тело формируется один раз, при записи: повтор доставки обязан отправить ровно тот же JSON, иначе
 * подпись и дедупликация на стороне потребителя перестанут работать (FR-9.3).
 */
@Component
public class OutboxWriter {

    /** Имя поля в теле события по типу агрегата: контракт FR-9.3 называет объект согласия {@code consent}. */
    private static final Map<String, String> PAYLOAD_KEYS =
            Map.of("consent", "consent", "consent_form", "form", "third_party", "thirdParty", "import_job", "import");

    private final OutboxEventRepository outbox;
    private final NotificationSubjectPort subjects;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(
            OutboxEventRepository outbox, NotificationSubjectPort subjects, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.subjects = subjects;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(CusEvent event) {
        UUID deliveryId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event.eventType());
        body.put("occurredAt", clock.instant().toString());
        body.put("deliveryId", deliveryId.toString());

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("id", event.aggregateId());
        aggregate.putAll(event.payload());
        body.put(PAYLOAD_KEYS.getOrDefault(event.aggregateType(), event.aggregateType()), aggregate);

        if (event.subjectId() != null) {
            // Наружу уходит только внешний идентификатор: ПДн в теле события недопустимы (NFR-3, FR-9.3).
            subjects.find(event.subjectId())
                    .ifPresent(subject -> body.put("subject", Map.of("externalId", subject.externalId())));
        }

        outbox.save(new OutboxEvent(
                deliveryId,
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                toJson(body),
                clock.instant()));
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать событие для outbox", e);
        }
    }
}
