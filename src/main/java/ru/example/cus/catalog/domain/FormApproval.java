package ru.example.cus.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Запись истории согласования формы (FR-2.2). Не редактируется: решение принято однажды. */
@Entity
@Table(name = "form_approval")
public class FormApproval {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false)
    private ConsentForm form;

    @Column(name = "role_required", nullable = false, length = 32)
    private String roleRequired;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_login", length = 128)
    private String userLogin;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32)
    private ApprovalDecision decision;

    @Column(name = "comment")
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected FormApproval() {
        // for JPA
    }

    public FormApproval(
            UUID id,
            ConsentForm form,
            String roleRequired,
            UUID userId,
            String userLogin,
            ApprovalDecision decision,
            String comment,
            Instant decidedAt) {
        this.id = id;
        this.form = form;
        this.roleRequired = roleRequired;
        this.userId = userId;
        this.userLogin = userLogin;
        this.decision = decision;
        this.comment = comment;
        this.decidedAt = decidedAt;
    }

    public UUID getId() {
        return id;
    }

    public ConsentForm getForm() {
        return form;
    }

    public String getRoleRequired() {
        return roleRequired;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserLogin() {
        return userLogin;
    }

    public ApprovalDecision getDecision() {
        return decision;
    }

    public String getComment() {
        return comment;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
