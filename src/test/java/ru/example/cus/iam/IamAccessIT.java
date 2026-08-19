package ru.example.cus.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.iam.domain.AppUser;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.TestAccounts;

/** Приёмка этапа 1: матрица ролей Приложения E закрыта тестами доступа (FR-11.2, deny by default). */
class IamAccessIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Test
    void anonymous_calls_are_rejected() {
        assertThat(restTemplate.getForEntity("/api/v1/users", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate
                        .getForEntity("/api/v1/audit/events", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void user_management_belongs_to_the_administrator_only() {
        assertThat(status("/api/v1/users", RoleCode.ADMIN)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/users", RoleCode.DPO)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/users", RoleCode.MANAGER)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/users", RoleCode.AUDITOR)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void audit_journals_are_readable_by_auditor_dpo_and_administrator() {
        assertThat(status("/api/v1/audit/events", RoleCode.AUDITOR)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/audit/events", RoleCode.DPO)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/audit/events", RoleCode.ADMIN)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/audit/access-log", RoleCode.AUDITOR)).isEqualTo(HttpStatus.OK);

        assertThat(status("/api/v1/audit/events", RoleCode.MANAGER)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/audit/events", RoleCode.MARKETING)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/audit/events", RoleCode.LAWYER)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_issues_a_token_that_opens_the_api() {
        AppUser admin = accounts.create(RoleCode.ADMIN.name());

        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("login", admin.getLogin(), "password", TestAccounts.PASSWORD), Map.class);

        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) tokens.getBody().get("accessToken");
        assertThat(accessToken).isNotBlank();

        ResponseEntity<String> users = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET, new HttpEntity<>(TestAccounts.bearer(accessToken)), String.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refresh_token_cannot_be_used_as_an_access_token() {
        AppUser admin = accounts.create(RoleCode.ADMIN.name());
        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("login", admin.getLogin(), "password", TestAccounts.PASSWORD), Map.class);
        String refreshToken = (String) tokens.getBody().get("refreshToken");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET, new HttpEntity<>(TestAccounts.bearer(refreshToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrong_password_is_rejected_and_repeated_attempts_lock_the_account() {
        AppUser user = accounts.create(RoleCode.MANAGER.name());
        Map<String, String> wrong = Map.of("login", user.getLogin(), "password", "wrong-password");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(restTemplate
                            .postForEntity("/api/v1/auth/login", wrong, String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The fifth failure trips the lock of FR-11.1; afterwards even the correct password has to wait.
        restTemplate.postForEntity("/api/v1/auth/login", wrong, String.class);

        ResponseEntity<String> locked = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("login", user.getLogin(), "password", TestAccounts.PASSWORD),
                String.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getBody()).contains("urn:cus:error:too-many-requests");
    }

    @Test
    void unknown_login_is_indistinguishable_from_a_wrong_password() {
        ResponseEntity<String> unknown = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("login", "no-such-user", "password", "whatever"), String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody()).contains("urn:cus:error:unauthorized");
    }

    private HttpStatus status(String path, RoleCode role) {
        ResponseEntity<String> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(accounts.authorizationFor(role.name())), String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
