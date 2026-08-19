package ru.example.cus.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.cus.common.domain.AuditableEntity;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ConsentStatus;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.domain.SignatureType;

/**
 * Факт выражения воли субъектом по конкретному пункту конкретной версии формы (§2, §6).
 *
 * <p>Ссылки на тип, форму и третье лицо хранятся идентификаторами: модуль registry не тянет агрегаты чужих
 * модулей (§5), а реквизиты для показа подставляет application-слой. Категории ПДн, цели и контрольная сумма
 * формы скопированы в строку — это снимок условий, который не должен поехать вслед за справочником (§8.5).
 */
@Entity
@Table(name = "consent")
public class Consent extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "consent_type_id", nullable = false)
    private UUID consentTypeId;

    @Column(name = "form_id")
    private UUID formId;

    @Column(name = "form_item_id")
    private UUID formItemId;

    @Column(name = "form_checksum", length = 71)
    private String formChecksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 64)
    private ConsentSource source;

    @Column(name = "source_ref", length = 255)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConsentStatus status = ConsentStatus.ACTIVE;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_source", length = 32)
    private RevocationSource revocationSource;

    @Column(name = "third_party_id")
    private UUID thirdPartyId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pdn_categories", nullable = false, columnDefinition = "text[]")
    private String[] pdnCategories = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "purposes", nullable = false, columnDefinition = "text[]")
    private String[] purposes = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_type", nullable = false, length = 32)
    private SignatureType signatureType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private String evidence = "{}";

    @Column(name = "superseded_by_id")
    private UUID supersededById;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    protected Consent() {
        // for JPA
    }

    public Consent(
            UUID id,
            UUID subjectId,
            UUID consentTypeId,
            UUID formId,
            UUID formItemId,
            String formChecksum,
            ConsentSource source,
            String sourceRef,
            Instant grantedAt,
            Instant validUntil,
            UUID thirdPartyId,
            List<String> pdnCategories,
            List<String> purposes,
            SignatureType signatureType,
            String evidence,
            String idempotencyKey) {
        this.id = id;
        this.subjectId = subjectId;
        this.consentTypeId = consentTypeId;
        this.formId = formId;
        this.formItemId = formItemId;
        this.formChecksum = formChecksum;
        this.source = source;
        this.sourceRef = sourceRef;
        this.grantedAt = grantedAt;
        this.validUntil = validUntil;
        this.thirdPartyId = thirdPartyId;
        this.pdnCategories = pdnCategories == null ? new String[0] : pdnCategories.toArray(String[]::new);
        this.purposes = purposes == null ? new String[0] : purposes.toArray(String[]::new);
        this.signatureType = signatureType;
        this.evidence = evidence == null ? "{}" : evidence;
        this.idempotencyKey = idempotencyKey;
    }

    /** FR-4.3: новое согласие того же типа замещает предыдущее эффективное, история сохраняется целиком. */
    public void supersedeBy(Consent newer) {
        if (supersededById != null || revokedAt != null) {
            return;
        }
        this.supersededById = newer.getId();
        this.status = ConsentStatus.SUPERSEDED;
    }

    /** FR-8.3: отзыв необратим и вступает в силу немедленно. */
    public void revoke(Instant moment, String reason, RevocationSource revocationSource) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = moment;
        this.revocationReason = reason;
        this.revocationSource = revocationSource;
        this.status = ConsentStatus.REVOKED;
    }

    /** Пересчёт материализованного статуса (FR-5.3). Возвращает true, если статус изменился. */
    public boolean refreshStatus(Instant now, int expiringDays) {
        ConsentStatus calculated =
                ConsentStatusCalculator.statusOf(revokedAt, supersededById != null, validUntil, now, expiringDays);
        if (calculated == status) {
            return false;
        }
        status = calculated;
        return true;
    }

    public ConsentStatus calculatedStatus(Instant now, int expiringDays) {
        return ConsentStatusCalculator.statusOf(revokedAt, supersededById != null, validUntil, now, expiringDays);
    }

    public boolean isEffective() {
        return revokedAt == null && supersededById == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public UUID getConsentTypeId() {
        return consentTypeId;
    }

    public UUID getFormId() {
        return formId;
    }

    public UUID getFormItemId() {
        return formItemId;
    }

    public String getFormChecksum() {
        return formChecksum;
    }

    public ConsentSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public RevocationSource getRevocationSource() {
        return revocationSource;
    }

    public UUID getThirdPartyId() {
        return thirdPartyId;
    }

    public List<String> getPdnCategories() {
        return List.of(pdnCategories == null ? new String[0] : Arrays.copyOf(pdnCategories, pdnCategories.length));
    }

    public List<String> getPurposes() {
        return List.of(purposes == null ? new String[0] : Arrays.copyOf(purposes, purposes.length));
    }

    public SignatureType getSignatureType() {
        return signatureType;
    }

    public String getEvidence() {
        return evidence;
    }

    public UUID getSupersededById() {
        return supersededById;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
