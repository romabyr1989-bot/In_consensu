package ru.example.inconsensu.integration.domain;

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

/** Задача импорта согласий с прогрессом и построчным отчётом (FR-4.5, UI-12). */
@Entity
@Table(name = "import_job")
public class ImportJob {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ImportJobStatus status = ImportJobStatus.PENDING;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "imported", nullable = false)
    private int imported;

    @Column(name = "rejected", nullable = false)
    private int rejected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", nullable = false, columnDefinition = "jsonb")
    private String report = "[]";

    @Column(name = "started_by", length = 128)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "payload")
    private String payload;

    protected ImportJob() {
        // for JPA
    }

    public ImportJob(UUID id, String source, String fileName, boolean dryRun, String startedBy, Instant startedAt) {
        this.id = id;
        this.source = source;
        this.fileName = fileName;
        this.dryRun = dryRun;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
    }

    /**
     * Содержимое файла пробного запуска (UI-12).
     *
     * <p>Хранится, чтобы боевой импорт запускался кнопкой, а не повторной загрузкой того же файла, и
     * стирается сразу после запуска: в файле ПДн, и держать его дольше нужного нельзя.
     */
    public void keepPayload(String payload) {
        this.payload = payload;
    }

    public void clearPayload() {
        this.payload = null;
    }

    public String getPayload() {
        return payload;
    }

    public void start(int total) {
        this.status = ImportJobStatus.RUNNING;
        this.total = total;
    }

    public void progress(int imported, int rejected) {
        this.imported = imported;
        this.rejected = rejected;
    }

    public void complete(int imported, int rejected, String report, Instant finishedAt) {
        this.status = ImportJobStatus.COMPLETED;
        this.imported = imported;
        this.rejected = rejected;
        this.report = report;
        this.finishedAt = finishedAt;
    }

    public void fail(String report, Instant finishedAt) {
        this.status = ImportJobStatus.FAILED;
        this.report = report;
        this.finishedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public ImportJobStatus getStatus() {
        return status;
    }

    public int getTotal() {
        return total;
    }

    public int getImported() {
        return imported;
    }

    public int getRejected() {
        return rejected;
    }

    public String getReport() {
        return report;
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
}
