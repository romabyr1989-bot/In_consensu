package ru.example.cus.channels.domain;

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
import ru.example.cus.common.domain.CommunicationChannel;

/** Задача асинхронной массовой проверки канала (этап 8, FR-6.4). */
@Entity
@Table(name = "bulk_check_job")
public class BulkCheckJob {

    public enum Status {
        PENDING("в очереди"),
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
    @Column(name = "channel", nullable = false, length = 32)
    private CommunicationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.PENDING;

    @Column(name = "requested", nullable = false)
    private int requested;

    @Column(name = "processed", nullable = false)
    private int processed;

    @Column(name = "allowed_count", nullable = false)
    private int allowedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "identifiers", nullable = false, columnDefinition = "jsonb")
    private String identifiers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Column(name = "started_by", length = 128)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error")
    private String error;

    protected BulkCheckJob() {
        // for JPA
    }

    public BulkCheckJob(
            UUID id,
            CommunicationChannel channel,
            String identifiers,
            int requested,
            String startedBy,
            Instant startedAt) {
        this.id = id;
        this.channel = channel;
        this.identifiers = identifiers;
        this.requested = requested;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
    }

    public void start() {
        this.status = Status.RUNNING;
    }

    public void progress(int processed, int allowedCount) {
        this.processed = processed;
        this.allowedCount = allowedCount;
    }

    public void complete(Instant finishedAt, String result, int processed, int allowedCount) {
        this.status = Status.DONE;
        this.finishedAt = finishedAt;
        this.result = result;
        this.processed = processed;
        this.allowedCount = allowedCount;
    }

    public void fail(Instant finishedAt, String error) {
        this.status = Status.FAILED;
        this.finishedAt = finishedAt;
        this.error = error;
    }

    public UUID getId() {
        return id;
    }

    public CommunicationChannel getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public int getRequested() {
        return requested;
    }

    public int getProcessed() {
        return processed;
    }

    public int getAllowedCount() {
        return allowedCount;
    }

    public String getIdentifiers() {
        return identifiers;
    }

    public String getResult() {
        return result;
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

    public String getError() {
        return error;
    }
}
