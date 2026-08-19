package ru.example.cus.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ConsentStatus;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.registry.application.ConsentQueryService;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;
import ru.example.cus.support.TestForms;

/** Корректность данных: материализованный статус, замещение по дате и слияние контактов при импорте. */
class DataCorrectnessIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ConsentQueryService consents;

    @Autowired
    private SubjectService subjects;

    @Autowired
    private TestForms testForms;

    @Test
    void expired_consent_gets_the_right_status_without_waiting_for_the_nightly_job() {
        ConsentForm form = testForms.publishTwoItemForm();
        UUID typeId = form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow()
                .getConsentType()
                .getId();
        Subject subject = subjects.upsert(newSubject());

        // FR-5.3: импортированное согласие с истёкшим сроком не должно числиться действующим.
        Consent imported = RunAs.roles(
                "test-integration",
                List.of("INTEGRATION"),
                () -> registration.registerImported(new ConsentRegistrationService.ImportedConsent(
                        subject.getId(),
                        typeId,
                        null,
                        null,
                        null,
                        ConsentSource.CLIENT_BASE_IMPORT,
                        "legacy",
                        Instant.now().minus(Duration.ofDays(800)),
                        Instant.now().minus(Duration.ofDays(1)),
                        null,
                        List.of("EMAIL"),
                        List.of("информирование"),
                        Map.of(
                                "importJobId",
                                UUID.randomUUID().toString(),
                                "legacySystem",
                                "CRM",
                                "note",
                                "перенос базы"),
                        "expired-" + UUID.randomUUID())));

        assertThat(imported.getStatus())
                .as("статус материализуется сразу при создании")
                .isEqualTo(ConsentStatus.EXPIRED);
        assertThat(consents.get(imported.getId()).status()).isEqualTo(ConsentStatus.EXPIRED);
    }

    @Test
    void a_backdated_consent_does_not_supersede_a_newer_one() {
        ConsentForm form = testForms.publishTwoItemForm();
        UUID typeId = form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow()
                .getConsentType()
                .getId();
        Subject subject = subjects.upsert(newSubject());

        Consent fresh = importConsent(subject, typeId, Instant.now().minus(Duration.ofDays(1)), "fresh");
        Consent backdated = importConsent(subject, typeId, Instant.now().minus(Duration.ofDays(400)), "old");

        // FR-4.3: строка импорта, идущая не по хронологии, не должна гасить более свежее согласие.
        assertThat(consents.get(fresh.getId()).consent().getSupersededById())
                .as("свежее согласие обязано остаться эффективным")
                .isNull();
        assertThat(consents.get(backdated.getId()).consent().getSupersededById())
                .as("зарегистрированное задним числом само становится заменённым")
                .isEqualTo(fresh.getId());
    }

    @Test
    void merging_upsert_keeps_contacts_loaded_by_previous_rows() {
        String externalId = "CRM-MERGE-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "merge-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru";

        subjects.upsert(new SubjectService.SubjectForm(
                externalId,
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-03-11", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, email, false))));

        // FR-4.5: следующая строка файла знает только телефон — email обязан сохраниться.
        Subject merged = subjects.upsertMerging(new SubjectService.SubjectForm(
                externalId,
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-03-11", true))));

        assertThat(merged.getContacts())
                .extracting(contact -> contact.getType().name())
                .contains("PHONE", "EMAIL");
        assertThat(merged.getContacts()).hasSize(2);
    }

    private Consent importConsent(Subject subject, UUID typeId, Instant grantedAt, String tag) {
        return RunAs.roles(
                "test-integration",
                List.of("INTEGRATION"),
                () -> registration.registerImported(new ConsentRegistrationService.ImportedConsent(
                        subject.getId(),
                        typeId,
                        null,
                        null,
                        null,
                        ConsentSource.CLIENT_BASE_IMPORT,
                        "legacy",
                        grantedAt,
                        null,
                        null,
                        List.of("EMAIL"),
                        List.of("информирование"),
                        Map.of(
                                "importJobId",
                                UUID.randomUUID().toString(),
                                "legacySystem",
                                "CRM",
                                "note",
                                "перенос базы"),
                        tag + "-" + UUID.randomUUID())));
    }

    private SubjectService.SubjectForm newSubject() {
        return new SubjectService.SubjectForm(
                "CRM-DATA-" + UUID.randomUUID().toString().substring(0, 8),
                "Чкалов",
                "Пётр",
                "Иванович",
                null,
                List.of(new SubjectService.ContactForm(
                        ContactType.EMAIL,
                        "data-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                        true)));
    }
}
