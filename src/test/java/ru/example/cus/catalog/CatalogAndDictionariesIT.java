package ru.example.cus.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.catalog.application.ConsentTypeService;
import ru.example.cus.catalog.domain.ConsentType;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentCategory;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.TestAccounts;

/** Этап 1: справочник типов согласий по Приложению B и единый эндпоинт справочников (FR-1.1, FR-11.4). */
class CatalogAndDictionariesIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentTypeService service;

    @Autowired
    private AuditService auditService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Test
    void seed_contains_every_type_of_appendix_b_with_its_dependency() {
        List<ConsentType> types = service.activeTypes();

        assertThat(types)
                .extracting(ConsentType::getCode)
                .contains(
                        "PDN_PROCESSING",
                        "ADVERTISING_PHONE",
                        "ADVERTISING_SMS",
                        "ADVERTISING_EMAIL",
                        "ADVERTISING_PUSH",
                        "ADVERTISING_MESSENGER",
                        "ADVERTISING_POSTAL",
                        "PDN_TRANSFER",
                        "PDN_DISTRIBUTION",
                        "LOYALTY_PROGRAM");

        ConsentType base = service.getByCode("PDN_PROCESSING");
        assertThat(base.getDependsOn()).isNull();
        assertThat(base.getChannels()).isEmpty();

        // FR-8.4: от базового типа зависят все рекламные, передача и распространение.
        assertThat(service.getByCode("ADVERTISING_EMAIL").getDependsOn().getCode())
                .isEqualTo("PDN_PROCESSING");
        assertThat(service.getByCode("ADVERTISING_EMAIL").getChannels()).containsExactly(CommunicationChannel.EMAIL);
        assertThat(service.getByCode("PDN_TRANSFER").isRequiresThirdParty()).isTrue();
        assertThat(service.getByCode("PDN_TRANSFER").getDefaultValidity()).isEqualTo("P1Y");
        assertThat(service.getByCode("LOYALTY_PROGRAM").isBusinessSignificant()).isFalse();
    }

    @Test
    void type_can_be_created_updated_and_deactivated_with_an_audit_trail() {
        String code = "TEST_TYPE_" + System.nanoTime();
        service.create(
                code,
                new ConsentTypeService.ConsentTypeForm(
                        "Тестовый тип",
                        null,
                        ConsentCategory.OTHER,
                        Set.of(),
                        false,
                        "P6M",
                        "PDN_PROCESSING",
                        true,
                        500));

        service.update(
                code,
                new ConsentTypeService.ConsentTypeForm(
                        "Переименованный",
                        "описание",
                        ConsentCategory.ADVERTISING,
                        Set.of(CommunicationChannel.SMS),
                        false,
                        null,
                        "PDN_PROCESSING",
                        true,
                        501));

        ConsentType updated = service.getByCode(code);
        assertThat(updated.getNameRu()).isEqualTo("Переименованный");
        assertThat(updated.getChannels()).containsExactly(CommunicationChannel.SMS);
        assertThat(updated.getDefaultValidity()).isNull();

        service.deactivate(code);
        assertThat(service.getByCode(code).isActive()).isFalse();
        assertThat(service.activeTypes()).extracting(ConsentType::getCode).doesNotContain(code);

        assertThat(auditService.historyOf(ConsentTypeService.AGGREGATE_TYPE, code))
                .extracting(event -> event.getEventType())
                .containsExactly(AuditEventType.CREATED, AuditEventType.UPDATED, AuditEventType.DEACTIVATED);
    }

    @Test
    void duplicate_code_and_self_dependency_are_rejected() {
        assertThatThrownBy(() -> service.create(
                        "PDN_PROCESSING",
                        new ConsentTypeService.ConsentTypeForm(
                                "Дубль", null, ConsentCategory.OTHER, Set.of(), false, null, null, true, 1)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.update(
                        "PDN_PROCESSING",
                        new ConsentTypeService.ConsentTypeForm(
                                "Сам на себя",
                                null,
                                ConsentCategory.PROCESSING,
                                Set.of(),
                                false,
                                null,
                                "PDN_PROCESSING",
                                true,
                                10)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changing_the_catalog_is_closed_for_roles_without_the_right() {
        assertThat(post("/api/v1/consent-types/PDN_PROCESSING/deactivate", RoleCode.MARKETING))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/consent-types/PDN_PROCESSING/deactivate", RoleCode.AUDITOR))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/consent-types", RoleCode.MARKETING)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void dictionaries_are_served_in_russian_for_the_interface() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dictionaries",
                HttpMethod.GET,
                new HttpEntity<>(accounts.authorizationFor(RoleCode.MANAGER.name())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("pdn-categories")
                .contains("channels")
                .contains("Телефонный звонок")
                .contains("Фамилия, имя, отчество");

        assertThat(get("/api/v1/dictionaries/no-such-dictionary", RoleCode.MANAGER))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpStatus get(String path, RoleCode role) {
        return HttpStatus.valueOf(restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(accounts.authorizationFor(role.name())), String.class)
                .getStatusCode()
                .value());
    }

    private HttpStatus post(String path, RoleCode role) {
        return HttpStatus.valueOf(restTemplate
                .exchange(path, HttpMethod.POST, new HttpEntity<>(accounts.authorizationFor(role.name())), String.class)
                .getStatusCode()
                .value());
    }
}
