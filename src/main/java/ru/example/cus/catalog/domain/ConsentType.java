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
import java.time.Duration;
import java.time.Period;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.cus.common.domain.AuditableEntity;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentCategory;

/**
 * Бизнес-значимый вид разрешения (§2, FR-1.1).
 *
 * <p>Код неизменяем после создания: на него ссылаются внешние системы и импорт (FR-4.5). Вместо удаления —
 * деактивация: ранее выданные согласия этого типа продолжают действовать и учитываться (FR-1.1).
 */
@Entity
@Table(name = "consent_type")
public class ConsentType extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    @Column(name = "name_ru", nullable = false, length = 255)
    private String nameRu;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private ConsentCategory category;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "channels", nullable = false, columnDefinition = "text[]")
    private String[] channels = new String[0];

    @Column(name = "requires_third_party", nullable = false)
    private boolean requiresThirdParty;

    /** ISO-8601 duration; null означает «до отзыва» (FR-4.3). */
    @Column(name = "default_validity", length = 32)
    private String defaultValidity;

    /**
     * Загружается жадно намеренно: код зависимости входит в каждый ответ API и в каскадный отзыв (FR-8.4), а
     * {@code open-in-view} выключен, поэтому ленивая ссылка ломалась бы при сериализации. Справочник маленький.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "depends_on_type_id")
    private ConsentType dependsOn;

    @Column(name = "business_significant", nullable = false)
    private boolean businessSignificant = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ConsentType() {
        // for JPA
    }

    public ConsentType(UUID id, String code, String nameRu, ConsentCategory category) {
        this.id = id;
        this.code = code;
        this.nameRu = nameRu;
        this.category = category;
    }

    /** Проверяет, что строка срока разбирается как ISO-8601 duration, и возвращает её. */
    public static String normalizeValidity(String isoDuration) {
        if (isoDuration == null || isoDuration.isBlank()) {
            return null;
        }
        String value = isoDuration.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            if (value.startsWith("PT")) {
                Duration.parse(value);
            } else {
                Period.parse(value);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Срок должен быть в формате ISO-8601, например P1Y или P180D");
        }
        return value;
    }

    public void update(
            String nameRu,
            String description,
            ConsentCategory category,
            Set<CommunicationChannel> channels,
            boolean requiresThirdParty,
            String defaultValidity,
            ConsentType dependsOn,
            boolean businessSignificant,
            int sortOrder) {
        this.nameRu = nameRu;
        this.description = description;
        this.category = category;
        this.channels = channels.stream().map(Enum::name).toArray(String[]::new);
        this.requiresThirdParty = requiresThirdParty;
        this.defaultValidity = normalizeValidity(defaultValidity);
        this.dependsOn = dependsOn;
        this.businessSignificant = businessSignificant;
        this.sortOrder = sortOrder;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getNameRu() {
        return nameRu;
    }

    public String getDescription() {
        return description;
    }

    public ConsentCategory getCategory() {
        return category;
    }

    public Set<CommunicationChannel> getChannels() {
        return Arrays.stream(channels == null ? new String[0] : channels)
                .map(CommunicationChannel::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isRequiresThirdParty() {
        return requiresThirdParty;
    }

    public String getDefaultValidity() {
        return defaultValidity;
    }

    public ConsentType getDependsOn() {
        return dependsOn;
    }

    public boolean isBusinessSignificant() {
        return businessSignificant;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
