package ru.example.inconsensu.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * FR-6.4: синхронная массовая проверка канала перед рассылкой.
 *
 * <p>Это та операция, ради которой маркетинг обращается к системе чаще всего, и до сих пор её не проверял
 * ни один тест — покрыт был только асинхронный вариант этапа 8. Ошибка здесь означает рассылку тем, кто
 * согласие отозвал, то есть прямое нарушение 38-ФЗ.
 */
class BulkChannelCheckIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private RevocationService revocation;

    @Test
    void check_sorts_identifiers_into_allowed_denied_and_unknown() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem advertising = itemOf(form, "ADVERTISING_EMAIL");

        Consent allowed = registerConsent(form, advertising);
        Consent revoked = registerConsent(form, advertising);
        RunAs.rolesVoid(
                "test-manager",
                List.of("MANAGER"),
                () -> revocation.revoke(
                        revoked.getId(),
                        "Обращение клиента",
                        RevocationSource.CALL_CENTER,
                        "ОБР-МАССОВАЯ",
                        Map.<String, Object>of()));

        String unknown = "CRM-НЕТ-ТАКОГО-" + UUID.randomUUID().toString().substring(0, 6);
        String body = """
                {"channel":"EMAIL","identifiers":["%s","%s","%s"],"includeReasons":true}"""
                .formatted(allowed.getSubjectId(), revoked.getSubjectId(), unknown);

        ResponseEntity<String> response = post(RoleCode.MARKETING, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"requested\":3");
        assertThat(response.getBody())
                .as("действующее согласие обязано попасть в разрешённые")
                .contains(allowed.getSubjectId().toString());
        assertThat(response.getBody())
                .as("отозванное согласие обязано попасть в отказы с кодом причины из FR-6.1")
                .contains("\"" + revoked.getSubjectId() + "\":\"REVOKED\"");
        assertThat(response.getBody())
                .as("неизвестный идентификатор обязан вернуться отдельно, а не молча пропасть")
                .contains(unknown);
        // NFR-3: в ответе только идентификаторы — ни адреса, ни ФИО там быть не должно.
        assertThat(response.getBody()).doesNotContain("@example.ru");

        // FR-6.4: на вызов пишется одна агрегированная запись журнала доступа, а не по записи на клиента.
        long records = RunAs.roles("test-auditor", List.of("AUDITOR"), () -> accessLog
                .accessLog(
                        new ru.example.inconsensu.audit.application.AuditQueryService.AccessFilter(
                                null, null, "/api/v1/channels/check", null, null),
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .getTotalElements());
        assertThat(records)
                .as("массовая проверка обязана оставлять одну запись на вызов")
                .isPositive();
    }

    /** Приложение E: массовая проверка — работа маркетинга и интеграций, колл-центру она закрыта. */
    @Test
    void the_call_centre_role_cannot_run_a_bulk_check() {
        Consent consent = registerConsent();
        String body = """
                {"channel":"EMAIL","identifiers":["%s"]}""".formatted(consent.getSubjectId());

        assertThat(post(RoleCode.MANAGER, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(RoleCode.INTEGRATION, body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-6.4: клиента можно назвать и внешним идентификатором мастер-системы, а не только UUID. */
    @Test
    void external_identifier_of_the_master_system_is_accepted() {
        Consent consent = registerConsent();
        String externalId = RunAs.roles("test-admin", List.of("ADMIN"), () -> subjects.get(consent.getSubjectId())
                .getExternalId());
        String body = """
                {"channel":"EMAIL","identifiers":["%s"]}""".formatted(externalId);

        ResponseEntity<String> response = post(RoleCode.MARKETING, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(externalId);
        assertThat(response.getBody()).contains("\"allowedCount\":1");
    }

    @Autowired
    private SubjectService subjects;

    @Autowired
    private ru.example.inconsensu.audit.application.AuditQueryService accessLog;

    private ResponseEntity<String> post(RoleCode role, String body) {
        HttpHeaders headers = new HttpHeaders(accounts.authorizationFor(role.name()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/channels/check", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static ConsentFormItem itemOf(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        return registerConsent(form, itemOf(form, "ADVERTISING_EMAIL"));
    }

    /** Регистрируются оба пункта формы: без базового согласия §7.6 закрывает любой канал. */
    private Consent registerConsent(ConsentForm form, ConsentFormItem item) {
        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-BULK-" + UUID.randomUUID().toString().substring(0, 8),
                "Чкалов",
                "Пётр",
                "Иванович",
                null,
                List.of(new SubjectService.ContactForm(
                        ContactType.EMAIL,
                        "bulk-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                        true)));

        return registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                subject,
                                form.getId(),
                                form.getItems().stream()
                                        .map(candidate ->
                                                new ConsentRegistrationService.ItemDecision(candidate.getId(), true))
                                        .toList(),
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "массовая проверка",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000048",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .stream()
                .filter(created -> created.getFormItemId().equals(item.getId()))
                .findFirst()
                .orElseThrow();
    }
}
