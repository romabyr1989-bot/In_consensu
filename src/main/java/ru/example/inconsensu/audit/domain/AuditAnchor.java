package ru.example.inconsensu.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** Daily digest of the audit journal, meant to be fixed externally (FR-10.1). */
@Entity
@Table(name = "audit_anchor")
public class AuditAnchor {

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "events_count", nullable = false)
    private long eventsCount;

    @Column(name = "day_hash", nullable = false, length = 64)
    private String dayHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditAnchor() {
        // for JPA
    }

    public AuditAnchor(LocalDate day, long eventsCount, String dayHash, Instant createdAt) {
        this.day = day;
        this.eventsCount = eventsCount;
        this.dayHash = dayHash;
        this.createdAt = createdAt;
    }

    public LocalDate getDay() {
        return day;
    }

    public long getEventsCount() {
        return eventsCount;
    }

    public String getDayHash() {
        return dayHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
