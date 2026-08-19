package ru.example.inconsensu.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Запуск проверки целостности журнала (FR-10.4, UI-15).
 *
 * <p>Проверка идёт в фоне, поэтому её состояние живёт в базе: на 30 млн событий (NFR-1) синхронный ответ
 * упёрся бы в таймаут, а история проверок нужна аудитору как доказательство регулярного контроля.
 */
@Entity
@Table(name = "audit_verification")
public class AuditVerification {

    public enum Status {
        RUNNING("выполняется"),
        DONE("завершена"),
        FAILED("ошибка");

        private final String nameRu;

        Status(String nameRu) {
            this.nameRu = nameRu;
        }

        public String nameRu() {
            return nameRu;
        }
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.RUNNING;

    @Column(name = "started_by", length = 128)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "integrity", length = 16)
    private String integrity;

    @Column(name = "aggregates_checked", nullable = false)
    private long aggregatesChecked;

    @Column(name = "events_checked", nullable = false)
    private long eventsChecked;

    @Column(name = "anchors_checked", nullable = false)
    private long anchorsChecked;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "problems", nullable = false, columnDefinition = "jsonb")
    private String problems = "[]";

    @Column(name = "error")
    private String error;

    protected AuditVerification() {
        // for JPA
    }

    public AuditVerification(UUID id, String startedBy, Instant startedAt) {
        this.id = id;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
    }

    public void complete(
            Instant finishedAt,
            String integrity,
            long aggregatesChecked,
            long eventsChecked,
            long anchorsChecked,
            String problems) {
        this.status = Status.DONE;
        this.finishedAt = finishedAt;
        this.integrity = integrity;
        this.aggregatesChecked = aggregatesChecked;
        this.eventsChecked = eventsChecked;
        this.anchorsChecked = anchorsChecked;
        this.problems = problems == null || problems.isBlank() ? "[]" : problems;
    }

    public void fail(Instant finishedAt, String error) {
        this.status = Status.FAILED;
        this.finishedAt = finishedAt;
        this.error = error;
    }

    public UUID getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getIntegrity() {
        return integrity;
    }

    public long getAggregatesChecked() {
        return aggregatesChecked;
    }

    public long getEventsChecked() {
        return eventsChecked;
    }

    public long getAnchorsChecked() {
        return anchorsChecked;
    }

    public String getProblems() {
        return problems;
    }

    public String getError() {
        return error;
    }
}
