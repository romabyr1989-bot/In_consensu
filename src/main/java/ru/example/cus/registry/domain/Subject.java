package ru.example.cus.registry.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ru.example.cus.common.domain.AuditableEntity;

/**
 * Субъект персональных данных (§2, §6).
 *
 * <p>ЦУС не мастер-система по клиентам: хранится минимально необходимый состав, всё остальное живёт в CRM и
 * связывается через {@code external_id}.
 */
@Entity
@Table(name = "subject")
public class Subject extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "last_name", nullable = false, length = 128)
    private String lastName;

    @Column(name = "first_name", nullable = false, length = 128)
    private String firstName;

    @Column(name = "middle_name", length = 128)
    private String middleName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("type asc, primary desc")
    private List<SubjectContact> contacts = new ArrayList<>();

    protected Subject() {
        // for JPA
    }

    public Subject(
            UUID id, String externalId, String lastName, String firstName, String middleName, LocalDate birthDate) {
        this.id = id;
        this.externalId = externalId;
        this.lastName = lastName;
        this.firstName = firstName;
        this.middleName = middleName;
        this.birthDate = birthDate;
    }

    public void rename(String lastName, String firstName, String middleName, LocalDate birthDate) {
        this.lastName = lastName;
        this.firstName = firstName;
        this.middleName = middleName;
        this.birthDate = birthDate;
    }

    public void replaceContacts(List<SubjectContact> newContacts) {
        contacts.clear();
        contacts.addAll(newContacts);
    }

    public String getFullName() {
        return middleName == null || middleName.isBlank()
                ? lastName + " " + firstName
                : lastName + " " + firstName + " " + middleName;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public List<SubjectContact> getContacts() {
        return List.copyOf(contacts);
    }
}
