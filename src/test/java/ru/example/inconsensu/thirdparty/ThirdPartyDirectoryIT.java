package ru.example.inconsensu.thirdparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Этап 1: справочник третьих лиц и контроль срока договора (FR-7.1). */
class ThirdPartyDirectoryIT extends AbstractIntegrationTest {

    @Autowired
    private ThirdPartyService service;

    @Autowired
    private AuditService auditService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    private static String uniqueInn() {
        return String.valueOf(1000000000L + (System.nanoTime() % 8999999999L));
    }

    private ThirdPartyService.ThirdPartyForm momento(LocalDate contractValidUntil) {
        return new ThirdPartyService.ThirdPartyForm(
                "ООО «Моменто»",
                "Моменто",
                "1027700132195",
                "Москва, ул. Тестовая, 1",
                ThirdPartyRole.PROCESSOR,
                "ДС-2025/117",
                LocalDate.of(2025, 9, 1),
                contractValidUntil,
                Set.of("FIO", "PHONE", "POSTAL_ADDRESS", "EMAIL"),
                "dpo@momento.example");
    }

    @Test
    void third_party_is_created_and_audited() {
        String inn = uniqueInn();
        ThirdParty created = service.create(inn, momento(service.today().plusYears(1)));

        assertThat(created.getAllowedPdnCategories())
                .containsExactlyInAnyOrder("FIO", "PHONE", "POSTAL_ADDRESS", "EMAIL");
        assertThat(created.canReceiveData(service.today())).isTrue();
        assertThat(auditService.historyOf(
                        ThirdPartyService.AGGREGATE_TYPE, created.getId().toString()))
                .extracting(event -> event.getEventType())
                .contains(AuditEventType.CREATED);
        assertThat(service.getByInn(inn).getId()).isEqualTo(created.getId());
    }

    @Test
    void expired_contract_closes_the_third_party_for_new_transfers() {
        ThirdParty expired = service.create(uniqueInn(), momento(service.today().minusDays(1)));

        assertThat(expired.isContractExpired(service.today())).isTrue();
        assertThat(expired.canReceiveData(service.today())).isFalse();
        assertThat(expired.daysUntilContractEnds(service.today())).isNegative();
    }

    @Test
    void contracts_ending_soon_are_found_for_the_notification_trigger() {
        ThirdParty soon = service.create(uniqueInn(), momento(service.today().plusDays(20)));

        assertThat(service.contractsEndingWithin(30))
                .extracting(ThirdParty::getId)
                .contains(soon.getId());
        assertThat(service.contractsEndingWithin(10))
                .extracting(ThirdParty::getId)
                .doesNotContain(soon.getId());
    }

    @Test
    void duplicate_inn_unknown_category_and_inverted_contract_dates_are_rejected() {
        String inn = uniqueInn();
        service.create(inn, momento(service.today().plusYears(1)));

        assertThatThrownBy(() -> service.create(inn, momento(service.today().plusYears(1))))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.create(
                        uniqueInn(),
                        new ThirdPartyService.ThirdPartyForm(
                                "ООО «Икс»",
                                null,
                                null,
                                "адрес",
                                ThirdPartyRole.RECIPIENT,
                                null,
                                null,
                                null,
                                Set.of("НЕТ_ТАКОЙ_КАТЕГОРИИ"),
                                null)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.create(
                        uniqueInn(),
                        new ThirdPartyService.ThirdPartyForm(
                                "ООО «Игрек»",
                                null,
                                null,
                                "адрес",
                                ThirdPartyRole.RECIPIENT,
                                "Д-1",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2025, 1, 1),
                                Set.of(),
                                null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deactivation_replaces_deletion() {
        ThirdParty created = service.create(uniqueInn(), momento(service.today().plusYears(1)));

        service.deactivate(created.getId());

        assertThat(service.get(created.getId()).isActive()).isFalse();
        assertThat(service.get(created.getId()).canReceiveData(service.today())).isFalse();
    }

    @Test
    void directory_is_readable_by_everyone_and_editable_by_lawyer_dpo_admin() {
        assertThat(status(HttpMethod.GET, RoleCode.MARKETING)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, RoleCode.AUDITOR)).isEqualTo(HttpStatus.OK);

        UUID id = service.create(uniqueInn(), momento(service.today().plusYears(1)))
                .getId();
        assertThat(deactivateAs(id, RoleCode.MARKETING)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deactivateAs(id, RoleCode.LAWYER)).isEqualTo(HttpStatus.OK);
    }

    private HttpStatus status(HttpMethod method, RoleCode role) {
        return HttpStatus.valueOf(restTemplate
                .exchange(
                        "/api/v1/third-parties",
                        method,
                        new HttpEntity<>(accounts.authorizationFor(role.name())),
                        String.class)
                .getStatusCode()
                .value());
    }

    private HttpStatus deactivateAs(UUID id, RoleCode role) {
        return HttpStatus.valueOf(restTemplate
                .exchange(
                        "/api/v1/third-parties/" + id + "/deactivate",
                        HttpMethod.POST,
                        new HttpEntity<>(accounts.authorizationFor(role.name())),
                        String.class)
                .getStatusCode()
                .value());
    }
}
