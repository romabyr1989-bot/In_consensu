package ru.example.inconsensu.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.audit.domain.AuditEvent;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.AuthService;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/** FR-11.1, FR-11.2, FR-10.5: жизненный цикл учётной записи и следы, которые он оставляет в журналах. */
class UserManagementIT extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PdnAccessLogService pdnAccessLogService;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void creating_a_user_is_recorded_in_the_audit_journal_without_the_password() {
        AppUser created = accounts.create(RoleCode.LAWYER.name());

        var events = auditService.historyOf(
                AuthService.AGGREGATE_TYPE, created.getId().toString());

        assertThat(events).isNotEmpty();
        assertThat(events).extracting(AuditEvent::getEventType).contains(AuditEventType.CREATED);
        assertThat(events).allSatisfy(event -> assertThat(event.getPayload())
                .doesNotContain(TestAccounts.PASSWORD)
                .doesNotContain("passwordHash"));
    }

    @Test
    void roles_and_status_can_be_changed_and_the_change_is_audited() {
        AppUser user = accounts.create(RoleCode.MANAGER.name());

        AppUser updated =
                userService.update(user.getId(), "Новое Имя", "new@example.ru", Set.of(RoleCode.DPO.name()), false);

        assertThat(updated.getRoleCodes()).containsExactly(RoleCode.DPO.name());
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getFullName()).isEqualTo("Новое Имя");
        assertThat(auditService.historyOf(
                        AuthService.AGGREGATE_TYPE, user.getId().toString()))
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.UPDATED);
    }

    @Test
    void deactivated_user_can_no_longer_log_in() {
        AppUser user = accounts.create(RoleCode.MANAGER.name());
        userService.update(user.getId(), user.getFullName(), null, Set.of(RoleCode.MANAGER.name()), false);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("login", user.getLogin(), "password", TestAccounts.PASSWORD),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void password_reset_lets_the_user_in_with_the_new_password_only() {
        AppUser user = accounts.create(RoleCode.MANAGER.name());

        userService.resetPassword(user.getId(), "brand-new-password");

        assertThat(authService.login(user.getLogin(), "brand-new-password").accessToken())
                .isNotBlank();
    }

    @Test
    void refreshing_a_token_returns_a_usable_access_token() {
        AppUser user = accounts.create(RoleCode.ADMIN.name());
        AuthService.AuthTokens first = authService.login(user.getLogin(), TestAccounts.PASSWORD);

        AuthService.AuthTokens refreshed = authService.refresh(first.refreshToken());

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.login()).isEqualTo(user.getLogin());
        assertThat(refreshed.expiresInSeconds()).isPositive();
    }

    @Test
    void every_role_of_appendix_e_exists_in_the_dictionary() {
        assertThat(userService.allRoles())
                .extracting(role -> role.getCode())
                .containsExactlyInAnyOrder(
                        RoleCode.ADMIN.name(),
                        RoleCode.DPO.name(),
                        RoleCode.LAWYER.name(),
                        RoleCode.MANAGER.name(),
                        RoleCode.MARKETING.name(),
                        RoleCode.INTEGRATION.name(),
                        RoleCode.AUDITOR.name());
        assertThat(userService.allRoles())
                .allSatisfy(role -> assertThat(role.getNameRu()).isNotBlank());
    }

    @Test
    void personal_data_access_is_logged_for_single_and_bulk_reads() {
        UUID subjectId = UUID.randomUUID();

        pdnAccessLogService.recordSingle("/api/v1/subjects/{id}/card", subjectId);
        pdnAccessLogService.recordBulk("/api/v1/channels/check", 4200);

        ResponseEntity<String> log = restTemplate.exchange(
                "/api/v1/audit/access-log?size=100",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(accounts.authorizationFor(RoleCode.AUDITOR.name())),
                String.class);

        assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(log.getBody()).contains("/api/v1/channels/check").contains("4200");
    }
}
