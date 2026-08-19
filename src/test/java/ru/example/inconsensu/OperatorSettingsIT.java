package ru.example.inconsensu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/** FR-11.3: настройки оператора доступны ADMIN и DPO, изменения аудируются. */
class OperatorSettingsIT extends AbstractIntegrationTest {

    @Autowired
    private OperatorSettingsService settings;

    @Autowired
    private AuditService auditService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Test
    void seed_contains_the_keys_required_by_the_specification() {
        assertThat(settings.all())
                .containsKeys(
                        "operator.name",
                        "operator.address",
                        "dpo.email",
                        "inconsensu.timezone",
                        "inconsensu.status.expiring-days",
                        "inconsensu.approval.required-roles",
                        "inconsensu.revocation.cascade-enabled",
                        "inconsensu.selfservice.auth-mode",
                        "inconsensu.export.ttl");
        // Значение operator.name намеренно не проверяется: его меняют другие тесты, и проверка сделала бы
        // результат зависимым от порядка выполнения.
        assertThat(settings.value("inconsensu.status.expiring-days")).isEqualTo("30");
    }

    @Test
    void changing_a_setting_is_recorded_in_the_audit_journal() {
        settings.update(Map.of("operator.name", "ООО «Тестовый оператор»"));

        assertThat(settings.value("operator.name")).isEqualTo("ООО «Тестовый оператор»");
        assertThat(auditService.historyOf(OperatorSettingsService.AGGREGATE_TYPE, OperatorSettingsService.AGGREGATE_ID))
                .extracting(event -> event.getEventType())
                .contains(AuditEventType.SETTINGS_CHANGED);
    }

    @Test
    void unknown_key_is_rejected_instead_of_being_created_silently() {
        assertThatThrownBy(() -> settings.update(Map.of("operator.unknown", "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Неизвестная настройка");
        assertThatThrownBy(() -> settings.update(Map.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void settings_are_closed_for_roles_other_than_admin_and_dpo() {
        assertThat(status(RoleCode.ADMIN)).isEqualTo(HttpStatus.OK);
        assertThat(status(RoleCode.DPO)).isEqualTo(HttpStatus.OK);
        assertThat(status(RoleCode.MANAGER)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(RoleCode.LAWYER)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(RoleCode.AUDITOR)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpStatus status(RoleCode role) {
        return HttpStatus.valueOf(restTemplate
                .exchange(
                        "/api/v1/settings",
                        HttpMethod.GET,
                        new HttpEntity<>(accounts.authorizationFor(role.name())),
                        String.class)
                .getStatusCode()
                .value());
    }
}
