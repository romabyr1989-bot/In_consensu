package ru.example.cus.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.cus.common.domain.ActorType;
import ru.example.cus.common.domain.AuditEventType;

/**
 * One immutable record of the audit journal (FR-10.1).
 *
 * <p>There are no setters and no {@code @Version} on purpose: rows are appended and never touched again, which the
 * database enforces with a trigger and with role privileges (FR-10.2).
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuditEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private ActorType actorType;

    @Column(name = "actor_id", length = 128)
    private String actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(
            String aggregateType,
            String aggregateId,
            UUID subjectId,
            AuditEventType eventType,
            Instant occurredAt,
            ActorType actorType,
            String actorId,
            String payload,
            String prevHash,
            String hash) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.subjectId = subjectId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.payload = payload;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getPayload() {
        return payload;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
