package ru.example.inconsensu.thirdparty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.inconsensu.common.domain.AuditableEntity;
import ru.example.inconsensu.common.domain.ThirdPartyRole;

/**
 * Организация, которой оператор передаёт ПДн (§2, FR-7.1).
 *
 * <p>Срок договора — не украшение: пока он не истёк, можно регистрировать согласия на передачу, после —
 * нельзя, а действующие помечаются в карточке (FR-7.1, FR-7.2).
 */
@Entity
@Table(name = "third_party")
public class ThirdParty extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 512)
    private String name;

    @Column(name = "short_name", length = 255)
    private String shortName;

    @Column(name = "inn", nullable = false, length = 12)
    private String inn;

    @Column(name = "ogrn", length = 15)
    private String ogrn;

    @Column(name = "address", nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private ThirdPartyRole role;

    @Column(name = "contract_number", length = 128)
    private String contractNumber;

    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "contract_valid_until")
    private LocalDate contractValidUntil;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_pdn_categories", nullable = false, columnDefinition = "text[]")
    private String[] allowedPdnCategories = new String[0];

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected ThirdParty() {
        // for JPA
    }

    public ThirdParty(UUID id, String name, String inn, String address, ThirdPartyRole role) {
        this.id = id;
        this.name = name;
        this.inn = inn;
        this.address = address;
        this.role = role;
    }

    /** Договор истёк — новые согласия на передачу этому лицу регистрировать нельзя (FR-7.1). */
    public boolean isContractExpired(LocalDate today) {
        return contractValidUntil != null && contractValidUntil.isBefore(today);
    }

    /** Календарных дней до окончания договора; null, если срок не задан. Отрицательное значение — договор истёк. */
    public Long daysUntilContractEnds(LocalDate today) {
        return contractValidUntil == null
                ? null
                : java.time.temporal.ChronoUnit.DAYS.between(today, contractValidUntil);
    }

    /** Готов принимать данные: активен и договор действует. */
    public boolean canReceiveData(LocalDate today) {
        return active && !isContractExpired(today);
    }

    public void update(
            String name,
            String shortName,
            String ogrn,
            String address,
            ThirdPartyRole role,
            String contractNumber,
            LocalDate contractDate,
            LocalDate contractValidUntil,
            Set<String> allowedPdnCategories,
            String contactEmail) {
        this.name = name;
        this.shortName = shortName;
        this.ogrn = ogrn;
        this.address = address;
        this.role = role;
        this.contractNumber = contractNumber;
        this.contractDate = contractDate;
        this.contractValidUntil = contractValidUntil;
        this.allowedPdnCategories =
                allowedPdnCategories == null ? new String[0] : allowedPdnCategories.toArray(String[]::new);
        this.contactEmail = contactEmail;
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getInn() {
        return inn;
    }

    public String getOgrn() {
        return ogrn;
    }

    public String getAddress() {
        return address;
    }

    public ThirdPartyRole getRole() {
        return role;
    }

    public String getContractNumber() {
        return contractNumber;
    }

    public LocalDate getContractDate() {
        return contractDate;
    }

    public LocalDate getContractValidUntil() {
        return contractValidUntil;
    }

    public Set<String> getAllowedPdnCategories() {
        return Arrays.stream(allowedPdnCategories == null ? new String[0] : allowedPdnCategories)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public boolean isActive() {
        return active;
    }
}
