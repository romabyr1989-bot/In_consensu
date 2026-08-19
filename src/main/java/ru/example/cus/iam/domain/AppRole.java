package ru.example.cus.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Role from the matrix of Приложение E. Seeded by migration; roles are not created at runtime. */
@Entity
@Table(name = "app_role")
public class AppRole {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name_ru", nullable = false, length = 128)
    private String nameRu;

    @Column(name = "description")
    private String description;

    protected AppRole() {
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

    public String getDescription() {
        return description;
    }
}
