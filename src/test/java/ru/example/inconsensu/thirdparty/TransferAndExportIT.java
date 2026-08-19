package ru.example.inconsensu.thirdparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestForms;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Приёмка этапа 4: передачи третьим лицам и выгрузка партнёру с журналом и TTL (FR-7.2 … FR-7.4). */
class TransferAndExportIT extends AbstractIntegrationTest {

    @Autowired
    private ThirdPartyService thirdParties;

    @Autowired
    private ru.example.inconsensu.thirdparty.application.TransferService transfers;

    @Autowired
    private PartnerExportService exports;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private TestForms testForms;

    private ThirdParty momento;
    private ConsentForm form;
    private UUID subjectId;

    private static String uniqueInn() {
        return String.valueOf(1000000000L + (System.nanoTime() % 8999999999L));
    }

    @BeforeEach
    void setUp() {
        momento = thirdParties.create(
                uniqueInn(),
                new ThirdPartyService.ThirdPartyForm(
                        "ООО «Моменто»",
                        "Моменто",
                        null,
                        "Москва, ул. Курьерская, 7",
                        ThirdPartyRole.PROCESSOR,
                        "ДС-2025/117",
                        LocalDate.now().minusMonths(1),
                        LocalDate.now().plusYears(1),
                        // Договор разрешает три категории; согласие ниже даст четыре — пересечение должно сработать.
                        Set.of("FIO", "PHONE", "POSTAL_ADDRESS"),
                        "dpo@momento.example"));

        form = testForms.publishFormWithTransfer(momento.getId(), List.of("FIO", "PHONE", "EMAIL", "POSTAL_ADDRESS"));
        subjectId = registerConsents();
    }

    private UUID registerConsents() {
        var subject = new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-41", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, "travin@example.ru", true),
                        new SubjectService.ContactForm(ContactType.POSTAL_ADDRESS, "Москва, ул. Кленовая, 3", true)));

        var result = registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        form.getItems().stream()
                                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                                .toList(),
                        Instant.now(),
                        ConsentSource.SUPPLEMENTARY_AGREEMENT,
                        "ДС-2025/117",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000041",
                                "otpVerifiedAt", Instant.now().toString(),
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));
        return result.created().get(0).getSubjectId();
    }

    @Test
    void transfer_lists_only_categories_covered_by_both_the_consent_and_the_contract() {
        var permissions = transfers.transfersOf(subjectId);

        assertThat(permissions).hasSize(1);
        // EMAIL есть в согласии, но договор его не покрывает — в передачу он попасть не должен.
        assertThat(permissions.get(0).allowedCategories())
                .containsExactlyInAnyOrder("FIO", "PHONE", "POSTAL_ADDRESS")
                .doesNotContain("EMAIL");
        assertThat(permissions.get(0).thirdPartyName()).isEqualTo("ООО «Моменто»");
    }

    @Test
    void check_answers_precisely_which_categories_may_be_transferred() {
        var allowed = transfers.check(subjectId, momento.getId(), List.of("FIO", "PHONE"));
        var partly = transfers.check(subjectId, momento.getId(), List.of("FIO", "EMAIL"));

        assertThat(allowed.allowed()).isTrue();
        assertThat(partly.allowed()).isFalse();
        assertThat(partly.deniedCategories()).containsExactly("EMAIL");
    }

    @Test
    void export_contains_only_permitted_categories_and_is_logged_with_a_checksum() {
        var export = exports.create(momento.getId(), "csv");

        assertThat(export.getRecordsCount()).isPositive();
        assertThat(export.getFileChecksum()).startsWith("sha256:");
        assertThat(export.getContent())
                .contains("Травин Иван Сергеевич")
                .contains("+7 916 000-00-41")
                // Категория, не разрешённая договором, в файл не попадает (NFR-3, FR-7.4).
                .doesNotContain("travin@example.ru");
        assertThat(exports.listFor(momento.getId())).extracting(e -> e.getId()).contains(export.getId());
    }

    @Test
    void json_export_is_available_and_downloadable_until_the_ttl_expires() {
        var export = exports.create(momento.getId(), "json");

        assertThat(export.getFormat()).isEqualTo("json");
        assertThat(exports.download(export.getId()).getContent()).startsWith("[");
        assertThat(export.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void export_is_refused_when_the_contract_has_expired() {
        thirdParties.update(
                momento.getId(),
                new ThirdPartyService.ThirdPartyForm(
                        momento.getName(),
                        null,
                        null,
                        momento.getAddress(),
                        ThirdPartyRole.PROCESSOR,
                        "ДС-2025/117",
                        LocalDate.now().minusYears(2),
                        LocalDate.now().minusDays(1),
                        Set.of("FIO"),
                        null));

        assertThatThrownBy(() -> exports.create(momento.getId(), "csv"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("договор");
    }

    @Test
    void unsupported_format_is_rejected() {
        assertThatThrownBy(() -> exports.create(momento.getId(), "xlsx"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("csv");
    }
}
