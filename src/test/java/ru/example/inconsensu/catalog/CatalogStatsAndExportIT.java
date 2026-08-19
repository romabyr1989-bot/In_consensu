package ru.example.inconsensu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.catalog.application.CatalogExportService;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** FR-3.3 и FR-3.4: выгрузка каталога с формами и пунктами и разрезы статистики. */
class CatalogStatsAndExportIT extends AbstractIntegrationTest {

    @Autowired
    private CatalogStatsService stats;

    @Autowired
    private CatalogExportService export;

    @Autowired
    private ThirdPartyService thirdParties;

    @Autowired
    private ConsentTypeService consentTypes;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private TestForms testForms;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    private ThirdParty partner;
    private ConsentForm form;

    private static String uniqueInn() {
        return String.valueOf(1000000000L + (System.nanoTime() % 8999999999L));
    }

    @BeforeEach
    void setUp() {
        partner = thirdParties.create(
                uniqueInn(),
                new ThirdPartyService.ThirdPartyForm(
                        "ООО «Статистика»",
                        "Статистика",
                        null,
                        "Москва, ул. Отчётная, 5",
                        ThirdPartyRole.PROCESSOR,
                        "ДС-2025/900",
                        LocalDate.now().minusMonths(1),
                        LocalDate.now().plusYears(1),
                        Set.of("FIO", "PHONE"),
                        "dpo@partner.example"));
        form = testForms.publishFormWithTransfer(partner.getId(), List.of("FIO", "PHONE"));
        registerConsentFor(form);
    }

    private void registerConsentFor(ConsentForm target) {
        var subject = new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Полевая",
                "Мария",
                "Ивановна",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-77", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, "polevaya@example.ru", true)));

        registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        target.getId(),
                        target.getItems().stream()
                                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                                .toList(),
                        Instant.now(),
                        ConsentSource.SUPPLEMENTARY_AGREEMENT,
                        "ДС-2025/900",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000077",
                                "otpVerifiedAt", Instant.now().toString(),
                                "otpHash", "hash",
                                "ip", "10.0.0.7",
                                "userAgent", "Mozilla")));
    }

    @Test
    void statistics_break_consents_down_by_type_and_by_third_party() {
        CatalogStatsService.CatalogStats snapshot = stats.stats();

        var pdn = snapshot.byType().stream()
                .filter(type -> type.code().equals("PDN_PROCESSING"))
                .findFirst()
                .orElseThrow();
        assertThat(pdn.active()).isPositive();
        assertThat(pdn.expiring() + pdn.expired() + pdn.revoked() + pdn.superseded())
                .isGreaterThanOrEqualTo(0);

        var byPartner = snapshot.byThirdParty().stream()
                .filter(row -> row.id().equals(partner.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(byPartner.name()).isEqualTo("ООО «Статистика»");
        // Передача выдана на год: она действует и попадает в окно «истекают за 30 дней» только к концу срока.
        assertThat(byPartner.active()).isEqualTo(1);
        assertThat(byPartner.expiringSoon()).isZero();
    }

    /**
     * FR-1.1 требует, чтобы согласия деактивированного типа продолжали действовать и учитываться, а §6
     * запрещает удалять справочники. Раньше статистика и выгрузка перечисляли только активные типы, и
     * деактивация стирала тип вместе с его согласиями из отчётов, хотя согласия никуда не делись.
     *
     * <p>Тип для теста создаётся свой: деактивация seed-типа осталась бы в общей базе и повлияла бы на
     * соседние классы.
     */
    @Test
    void a_deactivated_type_keeps_its_consents_in_statistics_and_export() {
        String code = "TEMPORARY_OFFER_" + UUID.randomUUID().toString().substring(0, 8);
        RunAs.roles(
                "stats-admin",
                List.of(RoleCode.ADMIN.name()),
                () -> consentTypes.create(
                        code,
                        new ConsentTypeService.ConsentTypeForm(
                                "Временное предложение",
                                "Тип создан тестом статистики",
                                ConsentCategory.OTHER,
                                Set.of(),
                                false,
                                null,
                                null,
                                false,
                                900)));
        ConsentForm temporaryForm = testForms.publish(List.of(new ConsentFormService.ItemForm(
                code, "Согласие на временное предложение", List.of("тест"), List.of("FIO"), null, null, false)));
        registerConsentFor(temporaryForm);

        assertThat(typeRow(stats.byType(), code).active()).isEqualTo(1);

        RunAs.roles("stats-admin", List.of(RoleCode.ADMIN.name()), () -> consentTypes.deactivate(code));

        assertThat(typeRow(stats.byType(), code).active())
                .as("согласие деактивированного типа продолжает учитываться (FR-1.1)")
                .isEqualTo(1);
        assertThat(export.snapshot().types())
                .filteredOn(row -> row.code().equals(code))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.active()).isFalse();
                    assertThat(row.activeConsents()).isEqualTo(1);
                });
    }

    private static CatalogStatsService.TypeStats typeRow(List<CatalogStatsService.TypeStats> rows, String code) {
        return rows.stream()
                .filter(row -> row.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Тип отсутствует в разрезе статистики: " + code));
    }

    @Test
    void export_snapshot_contains_types_forms_and_their_items() {
        CatalogExportService.CatalogSnapshot snapshot = export.snapshot();

        assertThat(snapshot.types())
                .extracting(CatalogExportService.TypeRow::code)
                .contains("PDN_PROCESSING");

        var exported = snapshot.forms().stream()
                .filter(row -> row.code().equals(form.getCode()))
                .findFirst()
                .orElseThrow();
        assertThat(exported.status()).isEqualTo("PUBLISHED");
        assertThat(exported.items()).hasSize(2);
        assertThat(exported.items())
                .extracting(CatalogExportService.ItemRow::consentTypeCode)
                .containsExactly("PDN_PROCESSING", "PDN_TRANSFER");
        assertThat(exported.items().get(1).thirdPartyName()).isEqualTo("ООО «Статистика»");
    }

    @Test
    void csv_export_serves_every_part_of_the_catalog() {
        assertThat(csv("types")).startsWith("code,nameRu,category").contains("PDN_PROCESSING");
        assertThat(csv("forms")).startsWith("code,version,title,status").contains(form.getCode());
        assertThat(csv("items"))
                .startsWith("formCode,formVersion,sortOrder,consentTypeCode")
                .contains("PDN_TRANSFER")
                .contains("ООО «Статистика»");
    }

    @Test
    void json_export_returns_the_whole_catalog_and_unknown_part_is_rejected() {
        var headers = accounts.authorizationFor(RoleCode.MANAGER.name());

        ResponseEntity<String> json = restTemplate.exchange(
                "/api/v1/catalog/export?format=json", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.getBody()).contains("\"types\"").contains("\"forms\"").contains("\"items\"");

        ResponseEntity<String> broken = restTemplate.exchange(
                "/api/v1/catalog/export?part=unknown", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String csv(String part) {
        var headers = accounts.authorizationFor(RoleCode.MANAGER.name());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/catalog/export?format=csv&part=" + part,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
