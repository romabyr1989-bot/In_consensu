package ru.example.inconsensu.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Категория персональных данных (§6). Справочник: пополняется, но не удаляется. */
@Entity
@Table(name = "pdn_category")
public class PdnCategory {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name_ru", nullable = false, length = 255)
    private String nameRu;

    @Column(name = "is_special", nullable = false)
    private boolean special;

    @Column(name = "is_biometric", nullable = false)
    private boolean biometric;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected PdnCategory() {
        // for JPA
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

    public boolean isSpecial() {
        return special;
    }

    public boolean isBiometric() {
        return biometric;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
