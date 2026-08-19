package ru.example.inconsensu.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.inconsensu.common.domain.AuditableEntity;

/**
 * Подписка внешней системы на события In consensu (FR-9.4, §7.9).
 *
 * <p>Пустой список типов означает «все события»: так подписка не ломается при появлении нового типа,
 * а потребитель, которому нужен только {@code consent.revoked}, перечисляет его явно.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "secret", nullable = false, length = 255)
    private String secret;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private String[] eventTypes = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    private String headers = "{}";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected WebhookSubscription() {
        // for JPA
    }

    public WebhookSubscription(UUID id, String name, String url, String secret) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.secret = secret;
    }

    public void update(String name, String url, Set<String> eventTypes, String headers, boolean active) {
        this.name = name;
        this.url = url;
        this.eventTypes = eventTypes == null ? new String[0] : eventTypes.toArray(String[]::new);
        this.headers = headers == null || headers.isBlank() ? "{}" : headers;
        this.active = active;
    }

    public void rotateSecret(String secret) {
        this.secret = secret;
    }

    /** Подписка получает событие, если активна и либо не сузила список типов, либо перечислила его. */
    public boolean accepts(String eventType) {
        return active && (eventTypes.length == 0 || Arrays.asList(eventTypes).contains(eventType));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public Set<String> getEventTypes() {
        return new LinkedHashSet<>(Arrays.asList(eventTypes));
    }

    public String getHeaders() {
        return headers;
    }

    public boolean isActive() {
        return active;
    }
}
