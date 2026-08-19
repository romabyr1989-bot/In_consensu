package ru.example.inconsensu.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.inconsensu.common.domain.AuditableEntity;

/**
 * Правило уведомления: о чём, за сколько дней, кому и куда (FR-9.1, FR-9.2, UI-14).
 *
 * <p>Правило без указания типа согласия действует на все типы; с указанием — только на свой. Пороги
 * задаются в днях до окончания срока и обрабатываются по одному: 30 дней и 7 дней — это два разных
 * уведомления, а не одно повторённое.
 */
@Entity
@Table(name = "notification_rule")
public class NotificationRule extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 64)
    private NotificationTrigger triggerType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "days_before", nullable = false, columnDefinition = "integer[]")
    private Integer[] daysBefore = new Integer[0];

    @Column(name = "consent_type_id")
    private UUID consentTypeId;

    @Column(name = "third_party_id")
    private UUID thirdPartyId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipient_emails", nullable = false, columnDefinition = "text[]")
    private String[] recipientEmails = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipient_roles", nullable = false, columnDefinition = "text[]")
    private String[] recipientRoles = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "channels", nullable = false, columnDefinition = "text[]")
    private String[] channels = new String[0];

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected NotificationRule() {
        // for JPA
    }

    public NotificationRule(UUID id, String name, NotificationTrigger triggerType) {
        this.id = id;
        this.name = name;
        this.triggerType = triggerType;
    }

    public void update(
            String name,
            NotificationTrigger triggerType,
            List<Integer> daysBefore,
            UUID consentTypeId,
            UUID thirdPartyId,
            Set<String> recipientEmails,
            Set<String> recipientRoles,
            Set<NotificationChannel> channels,
            boolean active) {
        this.name = name;
        this.triggerType = triggerType;
        this.daysBefore = daysBefore == null ? new Integer[0] : daysBefore.toArray(Integer[]::new);
        this.consentTypeId = consentTypeId;
        this.thirdPartyId = thirdPartyId;
        this.recipientEmails = recipientEmails == null ? new String[0] : recipientEmails.toArray(String[]::new);
        this.recipientRoles = recipientRoles == null ? new String[0] : recipientRoles.toArray(String[]::new);
        this.channels = channels == null
                ? new String[0]
                : channels.stream().map(Enum::name).toArray(String[]::new);
        this.active = active;
    }

    /** Правило без типа согласия применяется ко всем типам (FR-9.1). */
    public boolean appliesToType(UUID candidateTypeId) {
        return consentTypeId == null || consentTypeId.equals(candidateTypeId);
    }

    public boolean hasChannel(NotificationChannel channel) {
        return Arrays.asList(channels).contains(channel.name());
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public NotificationTrigger getTriggerType() {
        return triggerType;
    }

    public List<Integer> getDaysBefore() {
        return List.of(daysBefore);
    }

    public UUID getConsentTypeId() {
        return consentTypeId;
    }

    public UUID getThirdPartyId() {
        return thirdPartyId;
    }

    public Set<String> getRecipientEmails() {
        return new LinkedHashSet<>(Arrays.asList(recipientEmails));
    }

    public Set<String> getRecipientRoles() {
        return new LinkedHashSet<>(Arrays.asList(recipientRoles));
    }

    public Set<NotificationChannel> getChannels() {
        return Arrays.stream(channels)
                .map(NotificationChannel::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isActive() {
        return active;
    }
}
