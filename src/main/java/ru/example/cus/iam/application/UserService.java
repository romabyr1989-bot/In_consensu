package ru.example.cus.iam.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.iam.domain.AppRole;
import ru.example.cus.iam.domain.AppUser;
import ru.example.cus.iam.infrastructure.AppRoleRepository;
import ru.example.cus.iam.infrastructure.AppUserRepository;

/** Employee accounts and their roles (FR-11.1, FR-11.2, UI-16). */
@Service
public class UserService {

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(
            AppUserRepository users,
            AppRoleRepository roles,
            PasswordEncoder passwordEncoder,
            AuditService auditService) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<AppUser> list(Pageable pageable) {
        return users.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public boolean existsByLogin(String login) {
        return users.existsByLoginIgnoreCase(login);
    }

    @Transactional(readOnly = true)
    public AppUser get(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
    }

    @Transactional
    public AppUser create(String login, String password, String fullName, String email, Set<String> roleCodes) {
        if (users.existsByLoginIgnoreCase(login)) {
            throw ApiException.conflict("Пользователь с таким логином уже существует");
        }
        AppUser user = new AppUser(UUID.randomUUID(), login, passwordEncoder.encode(password), fullName, email);
        user.replaceRoles(resolveRoles(roleCodes));
        AppUser saved = users.save(user);
        auditService.record(
                AuthService.AGGREGATE_TYPE,
                saved.getId().toString(),
                AuditEventType.CREATED,
                Map.of("login", saved.getLogin(), "roles", saved.getRoleCodes()));
        return saved;
    }

    @Transactional
    public AppUser update(UUID id, String fullName, String email, Set<String> roleCodes, boolean active) {
        AppUser user = get(id);
        user.rename(fullName, email);
        user.replaceRoles(resolveRoles(roleCodes));
        user.setActive(active);
        AppUser saved = users.save(user);
        auditService.record(
                AuthService.AGGREGATE_TYPE,
                saved.getId().toString(),
                AuditEventType.UPDATED,
                Map.of("login", saved.getLogin(), "roles", saved.getRoleCodes(), "active", saved.isActive()));
        return saved;
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        AppUser user = get(id);
        user.changePassword(passwordEncoder.encode(newPassword));
        users.save(user);
        // The password itself never reaches the journal (NFR-3).
        auditService.record(
                AuthService.AGGREGATE_TYPE,
                user.getId().toString(),
                AuditEventType.UPDATED,
                Map.of("login", user.getLogin(), "change", "password-reset"));
    }

    /** Адреса всех активных пользователей указанных ролей — получатели уведомлений по ролям (FR-9.2). */
    @Transactional(readOnly = true)
    public Set<String> emailsByRoles(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Set.of();
        }
        return users.findActiveByRoleCodes(List.copyOf(roleCodes)).stream()
                .map(AppUser::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public List<AppRole> allRoles() {
        return roles.findAllByOrderByCodeAsc();
    }

    private Set<AppRole> resolveRoles(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите хотя бы одну роль пользователя");
        }
        List<AppRole> found = roles.findByCodeIn(roleCodes);
        if (found.size() != roleCodes.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Указана несуществующая роль");
        }
        return new LinkedHashSet<>(found);
    }
}
