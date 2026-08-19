package ru.example.cus.iam.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import ru.example.cus.common.domain.AuditableEntity;

/** Employee account (FR-11.1). Passwords are stored as BCrypt hashes and never leave the entity. */
@Entity
@Table(name = "app_user")
public class AppUser extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "login", nullable = false, length = 128)
    private String login;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany(
            fetch = FetchType.EAGER,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<AppRole> roles = new LinkedHashSet<>();

    protected AppUser() {
        // for JPA
    }

    public AppUser(UUID id, String login, String passwordHash, String fullName, String email) {
        this.id = id;
        this.login = login;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.email = email;
    }

    /** True while the account is blocked after too many failed logins (FR-11.1). */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void registerFailedLogin(int maxAttempts, Instant now, java.time.Duration lockDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
        }
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void rename(String fullName, String email) {
        this.fullName = fullName;
        this.email = email;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void replaceRoles(Set<AppRole> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public UUID getId() {
        return id;
    }

    public String getLogin() {
        return login;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Set<AppRole> getRoles() {
        return Set.copyOf(roles);
    }

    public Set<String> getRoleCodes() {
        return roles.stream()
                .map(AppRole::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
