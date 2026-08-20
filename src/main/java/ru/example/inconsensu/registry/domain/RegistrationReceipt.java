package ru.example.inconsensu.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Квитанция об обработанном запросе регистрации (FR-4.1).
 *
 * <p>Ключ идемпотентности хранится ещё и в самих согласиях, но запрос, все пункты которого отклонены,
 * согласий не создаёт — и без квитанции повтор такого запроса выполнялся бы заново. Здесь же остаётся
 * состав отклонённых пунктов: повтор обязан вернуть тот же ответ, а не пустой список.
 */
@Entity
@Table(name = "registration_receipt")
public class RegistrationReceipt {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "declined_item_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] declinedItemIds = new UUID[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RegistrationReceipt() {}

    public RegistrationReceipt(UUID id, String idempotencyKey, UUID subjectId, List<UUID> declined, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.subjectId = subjectId;
        this.declinedItemIds = declined == null ? new UUID[0] : declined.toArray(UUID[]::new);
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public List<UUID> getDeclinedItemIds() {
        return declinedItemIds == null ? List.of() : Arrays.asList(declinedItemIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
