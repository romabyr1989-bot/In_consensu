package ru.example.inconsensu.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Who looked at whose personal data, when and through which endpoint (FR-10.5).
 *
 * <p>Append-only like the audit journal; a bulk check writes one aggregated row per call (FR-6.4).
 */
@Entity
@Table(name = "pdn_access_log")
public class PdnAccessLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "endpoint", nullable = false, length = 255)
    private String endpoint;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "subjects_count", nullable = false)
    private int subjectsCount;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PdnAccessLogEntry() {
        // for JPA
    }

    public PdnAccessLogEntry(
            UUID userId, String endpoint, UUID subjectId, int subjectsCount, String requestId, Instant occurredAt) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.subjectId = subjectId;
        this.subjectsCount = subjectsCount;
        this.requestId = requestId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public int getSubjectsCount() {
        return subjectsCount;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
