package ru.example.cus.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Настройка оператора: реквизиты, контакт DPO, пороги, таймзона (FR-11.3). */
@Entity
@Table(name = "operator_settings")
public class OperatorSetting {

    @Id
    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "value")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    protected OperatorSetting() {
        // for JPA
    }

    public OperatorSetting(String key, String value, Instant updatedAt, String updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public void change(String value, Instant updatedAt, String updatedBy) {
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
