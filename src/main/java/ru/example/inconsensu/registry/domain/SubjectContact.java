package ru.example.inconsensu.registry.domain;

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
import ru.example.inconsensu.common.domain.AuditableEntity;
import ru.example.inconsensu.common.domain.ContactType;

/** Контакт субъекта (§6). Уникальности между субъектами нет: семья может делить один номер. */
@Entity
@Table(name = "subject_contact")
public class SubjectContact extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ContactType type;

    // NFR-3: при включённом inconsensu.crypto.enabled в колонке лежит шифртекст, конвертер прячет это от домена.
    @jakarta.persistence.Convert(converter = ru.example.inconsensu.common.application.EncryptedStringConverter.class)
    // NFR-3: под шифртекст нужно вдвое больше места, чем под открытое значение (V202608250000).
    @Column(name = "value", nullable = false, length = 2048)
    private String value;

    @jakarta.persistence.Convert(converter = ru.example.inconsensu.common.application.EncryptedStringConverter.class)
    @Column(name = "value_normalized", nullable = false, length = 2048)
    private String valueNormalized;

    /** HMAC нормализованного значения: по нему ищут, когда точное сравнение по шифртексту невозможно. */
    @Column(name = "search_hmac", length = 64)
    private String searchHmac;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    protected SubjectContact() {
        // for JPA
    }

    /**
     * @param validFrom с какого момента контакт действует (§8). Колонка была объявлена и всегда оставалась
     *     пустой: период действия контакта нечем было заполнить, и «действует с» на карточке не показать
     */
    public SubjectContact(
            UUID id, Subject subject, ContactType type, String value, boolean primary, Instant validFrom) {
        this.id = id;
        this.subject = subject;
        this.type = type;
        this.value = value.trim();
        this.valueNormalized = ContactNormalizer.normalize(type, value);
        this.primary = primary;
        this.validFrom = validFrom;
    }

    /** Заполняется при сохранении: домен не знает ни про ключи, ни про алгоритм (NFR-3). */
    public void applySearchHmac(String searchHmac) {
        this.searchHmac = searchHmac;
    }

    public String getSearchHmac() {
        return searchHmac;
    }

    public UUID getId() {
        return id;
    }

    public Subject getSubject() {
        return subject;
    }

    public ContactType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public String getValueNormalized() {
        return valueNormalized;
    }

    public boolean isPrimary() {
        return primary;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }
}
