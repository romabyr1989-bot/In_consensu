package ru.example.cus.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Одноразовая ссылка на страницу самообслуживания (UI-18).
 *
 * <p>Хранится хеш токена, а не сам токен: утечка таблицы не должна давать доступ к чужим согласиям (NFR-3).
 * Ссылка одноразовая — повторное открытие уже использованной ссылки равносильно её отсутствию.
 */
@Entity
@Table(name = "self_ui_session")
public class SelfUiSession {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "issued_by", length = 128)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "link_expires_at", nullable = false)
    private Instant linkExpiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "session_expires_at")
    private Instant sessionExpiresAt;

    protected SelfUiSession() {
        // for JPA
    }

    public SelfUiSession(
            UUID id, String tokenHash, UUID subjectId, String issuedBy, Instant issuedAt, Duration linkTtl) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.subjectId = subjectId;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
        this.linkExpiresAt = issuedAt.plus(linkTtl);
    }

    public boolean isLinkUsable(Instant now) {
        return usedAt == null && linkExpiresAt.isAfter(now);
    }

    /** Открытие ссылки: она гасится, а вместо неё начинается ограниченная по времени сессия страницы. */
    public void open(Instant now, Duration sessionTtl) {
        this.usedAt = now;
        this.sessionExpiresAt = now.plus(sessionTtl);
    }

    public boolean isSessionActive(Instant now) {
        return sessionExpiresAt != null && sessionExpiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public Instant getLinkExpiresAt() {
        return linkExpiresAt;
    }

    public Instant getSessionExpiresAt() {
        return sessionExpiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getIssuedBy() {
        return issuedBy;
    }
}
