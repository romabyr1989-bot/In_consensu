package ru.example.inconsensu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Пункт формы согласия (§6, FR-1.2).
 *
 * <p>Каждый пункт привязан к типу согласия: именно из пункта рождается отдельный экземпляр согласия, поэтому
 * цели, категории ПДн и срок хранятся здесь, а не в форме целиком.
 */
@Entity
@Table(name = "consent_form_item")
public class ConsentFormItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false)
    private ConsentForm form;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "consent_type_id", nullable = false)
    private ConsentType consentType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "text", nullable = false)
    private String text;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "purposes", nullable = false, columnDefinition = "text[]")
    private String[] purposes = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pdn_categories", nullable = false, columnDefinition = "text[]")
    private String[] pdnCategories = new String[0];

    /**
     * Идентификатор третьего лица, а не ссылка на сущность: по §5 модули не тянут чужие агрегаты, реквизиты
     * подставляет application-слой через {@code ThirdPartyService}. Ссылочная целостность остаётся на внешнем ключе.
     */
    @Column(name = "third_party_id")
    private UUID thirdPartyId;

    @Column(name = "validity", length = 32)
    private String validity;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;

    protected ConsentFormItem() {
        // for JPA
    }

    public ConsentFormItem(
            UUID id,
            ConsentForm form,
            ConsentType consentType,
            int sortOrder,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            UUID thirdPartyId,
            String validity,
            boolean mandatory) {
        this.id = id;
        this.form = form;
        this.consentType = consentType;
        this.sortOrder = sortOrder;
        this.text = text;
        this.purposes = purposes == null ? new String[0] : purposes.toArray(String[]::new);
        this.pdnCategories = pdnCategories == null ? new String[0] : pdnCategories.toArray(String[]::new);
        this.thirdPartyId = thirdPartyId;
        this.validity = ConsentType.normalizeValidity(validity);
        this.mandatory = mandatory;
    }

    /** Копия пункта для новой версии формы (FR-1.5). */
    public ConsentFormItem copyTo(ConsentForm newForm) {
        return new ConsentFormItem(
                UUID.randomUUID(),
                newForm,
                consentType,
                sortOrder,
                text,
                getPurposes(),
                getPdnCategories(),
                thirdPartyId,
                validity,
                mandatory);
    }

    public UUID getId() {
        return id;
    }

    public ConsentForm getForm() {
        return form;
    }

    public ConsentType getConsentType() {
        return consentType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getText() {
        return text;
    }

    public List<String> getPurposes() {
        return List.of(purposes == null ? new String[0] : Arrays.copyOf(purposes, purposes.length));
    }

    public List<String> getPdnCategories() {
        return List.of(pdnCategories == null ? new String[0] : Arrays.copyOf(pdnCategories, pdnCategories.length));
    }

    public UUID getThirdPartyId() {
        return thirdPartyId;
    }

    public String getValidity() {
        return validity;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
