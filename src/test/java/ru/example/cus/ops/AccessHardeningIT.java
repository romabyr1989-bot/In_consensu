package ru.example.cus.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.RevocationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;
import ru.example.cus.support.TestAccounts;
import ru.example.cus.support.TestForms;
import ru.example.cus.thirdparty.application.TransferService;

/**
 * Проверки прав и правила §8.3 п.3, найденные сверкой реализации с ТЗ.
 *
 * <p>Каждый тест закрывает конкретную дыру: доступ сотрудника к самообслуживанию клиента, доступ к
 * доказательствам без права на ПДн, управление подписками не той ролью и передача данных без живого
 * базового согласия.
 */
class AccessHardeningIT extends AbstractIntegrationTest {

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

    @Autowired
    private TransferService transfers;

    @Autowired
    private ru.example.cus.registry.application.ConsentQueryService consents;

    @Autowired
    private ru.example.cus.thirdparty.application.ThirdPartyService thirdParties;

    private ResponseEntity<String> call(String path, HttpMethod method, RoleCode role, String body) {
        HttpHeaders headers = accounts.authorizationFor(role.name());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void self_service_api_is_closed_for_ordinary_employees() {
        // FR-8.1: отзыв от имени клиента — операция личного кабинета, а не сотрудника с любой ролью.
        for (RoleCode role : List.of(RoleCode.MARKETING, RoleCode.LAWYER, RoleCode.AUDITOR, RoleCode.MANAGER)) {
            assertThat(call("/api/v1/self/consents?externalId=CRM-1002345", HttpMethod.GET, role, null)
                            .getStatusCode())
                    .as("роль %s не должна попадать в самообслуживание", role)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        // Личный кабинет ходит сервисным токеном роли INTEGRATION — ему доступ нужен.
        assertThat(call("/api/v1/self/consents?externalId=CRM-НЕТ-ТАКОГО", HttpMethod.GET, RoleCode.INTEGRATION, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void webhook_subscriptions_are_managed_by_admin_only() {
        // §9 и Приложение E: подписками управляет только ADMIN.
        assertThat(call("/api/v1/webhooks", HttpMethod.GET, RoleCode.INTEGRATION, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call("/api/v1/webhooks", HttpMethod.GET, RoleCode.ADMIN, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void audit_stays_open_to_dpo() {
        // §9: аудит читают AUDITOR, DPO и ADMIN — метод-уровневые аннотации не должны сужать список.
        assertThat(call("/api/v1/audit/events", HttpMethod.GET, RoleCode.DPO, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call("/api/v1/audit/verify", HttpMethod.GET, RoleCode.DPO, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void webhook_url_is_checked_against_the_allow_list() {
        // NFR-4: правило перестало быть мёртвым кодом — схема проверяется на живом сервисе.
        assertThat(call(
                                "/api/v1/webhooks",
                                HttpMethod.POST,
                                RoleCode.ADMIN,
                                "{\"name\":\"Плохая схема\",\"url\":\"ftp://crm.example.ru/hook\"}")
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfers_require_a_live_base_consent() {
        Consent baseConsent = registerConsents();
        UUID subjectId = baseConsent.getSubjectId();

        assertThat(RunAs.roles("test-dpo", List.of("DPO"), () -> transfers.transfersOf(subjectId)))
                .as("пока базовое согласие живо, передача разрешена")
                .isNotEmpty();

        RunAs.rolesVoid(
                "test-dpo",
                List.of("DPO"),
                () -> revocation.revoke(
                        baseConsent.getId(), "проверка правила", RevocationSource.CALL_CENTER, "ОБР-БАЗА", Map.of()));

        // §8.3 п.3: без живого PDN_PROCESSING передач нет, даже если согласие на передачу цело.
        assertThat(RunAs.roles("test-dpo", List.of("DPO"), () -> transfers.transfersOf(subjectId)))
                .as("после отзыва базового согласия передачи обязаны исчезнуть")
                .isEmpty();
    }

    /** Регистрирует базовое согласие и согласие на передачу; возвращает базовое. */
    private Consent registerConsents() {
        UUID thirdPartyId = RunAs.roles("test-dpo", List.of("DPO"), () -> thirdParties
                .create(
                        String.valueOf(7700000000L + (long) (Math.random() * 99999999)),
                        new ru.example.cus.thirdparty.application.ThirdPartyService.ThirdPartyForm(
                                "ООО «Проверка правила»",
                                null,
                                null,
                                "123001, Москва, ул. Тестовая, д. 2",
                                ru.example.cus.common.domain.ThirdPartyRole.PROCESSOR,
                                "ДП-2026/1",
                                java.time.LocalDate.now().minusYears(1),
                                java.time.LocalDate.now().plusYears(1),
                                Set.of("FIO", "EMAIL"),
                                "partner@example.ru"))
                .getId());

        ConsentForm form = testForms.publishFormWithTransfer(thirdPartyId, List.of("FIO", "EMAIL"));
        var items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();
        UUID baseItemId = form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals("PDN_PROCESSING"))
                .findFirst()
                .orElseThrow()
                .getId();

        var result = registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        new SubjectService.SubjectForm(
                                "CRM-BASE-" + UUID.randomUUID().toString().substring(0, 8),
                                "Чкалов",
                                "Пётр",
                                "Иванович",
                                null,
                                List.of(new SubjectService.ContactForm(
                                        ContactType.EMAIL,
                                        "base-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                                        true))),
                        form.getId(),
                        items,
                        Instant.now(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "проверка базового согласия",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000049",
                                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));

        return result.created().stream()
                .filter(consent -> baseItemId.equals(consent.getFormItemId()))
                .findFirst()
                .orElseThrow();
    }
}
