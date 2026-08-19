package ru.example.inconsensu.common.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Событие предметной области, которое должно уйти наружу (§8.6, FR-9.4).
 *
 * <p>Публикуется через Spring {@code ApplicationEvent} — единственный разрешённый §5 способ общения модулей
 * помимо application-сервисов. Модуль-издатель не знает ни про outbox, ни про webhooks: он лишь сообщает,
 * что произошло.
 *
 * <p>Слушатель синхронный и работает в транзакции издателя: запись в outbox фиксируется вместе с изменением
 * данных, иначе согласие окажется отозванным, а потребители об этом не узнают.
 */
public record CusEvent(
        String aggregateType, String aggregateId, String eventType, UUID subjectId, Map<String, Object> payload) {

    public static CusEvent of(
            String aggregateType, String aggregateId, String eventType, UUID subjectId, Map<String, Object> payload) {
        return new CusEvent(aggregateType, aggregateId, eventType, subjectId, payload == null ? Map.of() : payload);
    }
}
