package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.ConsentStatusJob;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestForms;

/** Приёмка этапа 3: регистрация согласий, идемпотентность, замещение и статусы (FR-4.1 … FR-4.4, FR-5.3). */
class ConsentRegistrationIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ConsentQueryService queries;

    @Autowired
    private ConsentStatusJob statusJob;

    @Autowired
    private ru.example.inconsensu.registry.application.ConsentEvidenceService evidenceService;

    @Autowired
    private TestForms testForms;

    @Autowired
    private SubjectService subjects;

    private ConsentForm form;

    @BeforeEach
    void setUp() {
        form = testForms.publishTwoItemForm();
    }

    private ConsentFormItem itemOf(String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }

    private SubjectService.SubjectForm newSubject() {
        return new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-41", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, "travin@example.ru", true)));
    }

    private static Map<String, Object> smsEvidence() {
        return Map.of(
                "phone", "+79160000041",
                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                "otpHash", "hash",
                "ip", "10.0.0.1",
                "userAgent", "Mozilla");
    }

    private ConsentRegistrationService.RegistrationRequest request(
            SubjectService.SubjectForm subject, List<ConsentRegistrationService.ItemDecision> items) {
        return new ConsentRegistrationService.RegistrationRequest(
                null,
                subject,
                form.getId(),
                items,
                Instant.now(),
                ConsentSource.WEBSITE_APPLICATION,
                "заявка №1",
                SignatureType.SIMPLE_ES_SMS,
                smsEvidence());
    }

    @Test
    void batch_registration_creates_one_consent_per_accepted_item() {
        var result = registration.register(
                UUID.randomUUID().toString(),
                request(
                        newSubject(),
                        List.of(
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true),
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("ADVERTISING_EMAIL").getId(), true))));

        assertThat(result.created()).hasSize(2);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.created()).allSatisfy(consent -> {
            assertThat(consent.getFormChecksum()).startsWith("sha256:");
            assertThat(consent.getSignatureType()).isEqualTo(SignatureType.SIMPLE_ES_SMS);
        });
    }

    @Test
    void declined_item_creates_no_consent_but_stays_a_provable_fact() {
        var result = registration.register(
                UUID.randomUUID().toString(),
                request(
                        newSubject(),
                        List.of(
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true),
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("ADVERTISING_EMAIL").getId(), false))));

        assertThat(result.created()).hasSize(1);
        assertThat(result.declinedItems())
                .containsExactly(itemOf("ADVERTISING_EMAIL").getId());
    }

    @Test
    void repeating_a_request_with_the_same_key_returns_the_original_result() {
        String key = UUID.randomUUID().toString();
        var subject = newSubject();
        var items = List.of(new ConsentRegistrationService.ItemDecision(
                itemOf("PDN_PROCESSING").getId(), true));

        var first = registration.register(key, request(subject, items));
        var replay = registration.register(key, request(subject, items));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.created())
                .extracting(Consent::getId)
                .containsExactlyElementsOf(
                        first.created().stream().map(Consent::getId).toList());
    }

    /**
     * FR-4.1: повтор обязан вернуть исходный результат независимо от того, создались ли согласия.
     *
     * <p>Ключ идемпотентности раньше хранился только внутри созданных согласий, поэтому запрос со всеми
     * отклонёнными пунктами не оставлял следа: повтор выполнялся заново и писал вторые события DECLINED.
     */
    @Test
    void repeating_a_request_where_every_item_was_declined_changes_nothing() {
        String key = UUID.randomUUID().toString();
        var subject = newSubject();
        var items = List.of(
                new ConsentRegistrationService.ItemDecision(
                        itemOf("PDN_PROCESSING").getId(), false),
                new ConsentRegistrationService.ItemDecision(
                        itemOf("ADVERTISING_EMAIL").getId(), false));

        var first = registration.register(key, request(subject, items));
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.created()).isEmpty();
        assertThat(first.declinedItems()).hasSize(2);

        var replay = registration.register(key, request(subject, items));

        assertThat(replay.idempotentReplay())
                .as("повтор обязан опознаваться и без единого созданного согласия")
                .isTrue();
        assertThat(replay.created()).isEmpty();
        assertThat(replay.declinedItems()).containsExactlyElementsOf(first.declinedItems());
    }

    /** Повтор при частичном отказе возвращает тот же состав отклонённых пунктов, а не пустой список. */
    @Test
    void replay_of_a_partial_refusal_repeats_the_declined_items() {
        String key = UUID.randomUUID().toString();
        var subject = newSubject();
        var items = List.of(
                new ConsentRegistrationService.ItemDecision(
                        itemOf("PDN_PROCESSING").getId(), true),
                new ConsentRegistrationService.ItemDecision(
                        itemOf("ADVERTISING_EMAIL").getId(), false));

        var first = registration.register(key, request(subject, items));
        var replay = registration.register(key, request(subject, items));

        assertThat(replay.declinedItems()).containsExactlyElementsOf(first.declinedItems());
        assertThat(replay.created())
                .extracting(Consent::getId)
                .containsExactlyElementsOf(
                        first.created().stream().map(Consent::getId).toList());
    }

    @Test
    void new_consent_of_the_same_type_supersedes_the_previous_one() {
        var subject = newSubject();
        var items = List.of(new ConsentRegistrationService.ItemDecision(
                itemOf("PDN_PROCESSING").getId(), true));

        Consent first = registration
                .register(UUID.randomUUID().toString(), request(subject, items))
                .created()
                .get(0);
        Consent second = registration
                .register(UUID.randomUUID().toString(), request(subject, items))
                .created()
                .get(0);

        assertThat(queries.get(first.getId()).status()).isEqualTo(ConsentStatus.SUPERSEDED);
        assertThat(queries.get(first.getId()).consent().getSupersededById()).isEqualTo(second.getId());
        assertThat(queries.get(second.getId()).status()).isEqualTo(ConsentStatus.ACTIVE);
        // §8.1: эффективное согласие на пару «субъект + тип» ровно одно.
        assertThat(queries.effectiveConsentsOf(first.getSubjectId())).hasSize(1);
    }

    @Test
    void validity_comes_from_the_item_then_from_the_type_then_stays_open_ended() {
        var result = registration.register(
                UUID.randomUUID().toString(),
                request(
                        newSubject(),
                        List.of(
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true),
                                new ConsentRegistrationService.ItemDecision(
                                        itemOf("ADVERTISING_EMAIL").getId(), true))));

        Consent processing = result.created().stream()
                .filter(consent -> consent.getValidUntil() == null)
                .findFirst()
                .orElseThrow();
        Consent advertising = result.created().stream()
                .filter(consent -> consent.getValidUntil() != null)
                .findFirst()
                .orElseThrow();

        // PDN_PROCESSING — бессрочное, у пункта рекламы задан P1Y.
        assertThat(processing.getValidUntil()).isNull();
        assertThat(advertising.getValidUntil())
                .isCloseTo(advertising.getGrantedAt().plus(365, ChronoUnit.DAYS), within(2, ChronoUnit.DAYS));
    }

    @Test
    void incomplete_evidence_is_rejected_with_a_field_level_error() {
        var incomplete = new ConsentRegistrationService.RegistrationRequest(
                null,
                newSubject(),
                form.getId(),
                List.of(new ConsentRegistrationService.ItemDecision(
                        itemOf("PDN_PROCESSING").getId(), true)),
                Instant.now(),
                ConsentSource.WEBSITE_APPLICATION,
                "заявка",
                SignatureType.SIMPLE_ES_SMS,
                Map.of("phone", "+79160000041"));

        assertThatThrownBy(() -> registration.register(UUID.randomUUID().toString(), incomplete))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("доказательств");
    }

    @Test
    void consent_dated_in_the_future_is_rejected() {
        var future = new ConsentRegistrationService.RegistrationRequest(
                null,
                newSubject(),
                form.getId(),
                List.of(new ConsentRegistrationService.ItemDecision(
                        itemOf("PDN_PROCESSING").getId(), true)),
                Instant.now().plus(1, ChronoUnit.HOURS),
                ConsentSource.WEBSITE_APPLICATION,
                "заявка",
                SignatureType.SIMPLE_ES_SMS,
                smsEvidence());

        assertThatThrownBy(() -> registration.register(UUID.randomUUID().toString(), future))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("будущем");
    }

    @Test
    void request_without_an_idempotency_key_is_rejected() {
        assertThatThrownBy(() -> registration.register(
                        "  ",
                        request(
                                newSubject(),
                                List.of(new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void status_materialization_agrees_with_the_status_calculated_on_read() {
        // FR-5.3 прямо требует, чтобы оба способа давали одинаковый результат.
        var result = registration.register(
                UUID.randomUUID().toString(),
                request(
                        newSubject(),
                        List.of(new ConsentRegistrationService.ItemDecision(
                                itemOf("ADVERTISING_EMAIL").getId(), true))));
        Consent consent = result.created().get(0);

        statusJob.refreshNow();

        var view = queries.get(consent.getId());
        assertThat(view.consent().getStatus()).isEqualTo(view.status());
    }

    @Test
    void dossier_proves_the_consent_with_the_exact_text_and_an_unbroken_chain() {
        Consent consent = registration
                .register(
                        UUID.randomUUID().toString(),
                        request(
                                newSubject(),
                                List.of(new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true))))
                .created()
                .get(0);

        var dossier = evidenceService.of(consent.getId());

        // FR-10.3: точный текст версии, сходящаяся контрольная сумма и целая цепочка событий.
        assertThat(dossier.checksumMatches()).isTrue();
        assertThat(dossier.formText()).contains("ООО «Тестовый оператор»").doesNotContain("Травин");
        assertThat(dossier.integrity())
                .isEqualTo(ru.example.inconsensu.audit.application.AuditIntegrityService.Integrity.OK);
        assertThat(dossier.events()).isNotEmpty();
        assertThat(dossier.integrityProblems()).isEmpty();
    }

    @Test
    void sensitive_evidence_fields_are_masked_before_they_leave_the_service() {
        Consent consent = registration
                .register(
                        UUID.randomUUID().toString(),
                        request(
                                newSubject(),
                                List.of(new ConsentRegistrationService.ItemDecision(
                                        itemOf("PDN_PROCESSING").getId(), true))))
                .created()
                .get(0);

        var masked = evidenceService.maskedEvidence(consent, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(masked).containsEntry("phone", "***").containsEntry("otpHash", "***");
    }

    /**
     * FR-4.4: повторная регистрация не стирает контакты клиента.
     *
     * <p>Внешняя система присылает в блоке subject тот контакт, по которому получено согласие. Раньше
     * список контактов заменялся целиком: согласие, оформленное по почте, лишало клиента телефона —
     * менеджер видел карточку без номера и не мог позвонить.
     */
    @Test
    void registering_for_a_known_client_adds_the_contact_instead_of_replacing_the_list() {
        SubjectService.SubjectForm initial = newSubject();
        registration.register(
                UUID.randomUUID().toString(),
                request(
                        initial,
                        List.of(new ConsentRegistrationService.ItemDecision(
                                itemOf("PDN_PROCESSING").getId(), true))));

        // Второй запрос по тому же клиенту, но только с почтой — как это делает внешняя форма на сайте.
        SubjectService.SubjectForm onlyEmail = new SubjectService.SubjectForm(
                initial.externalId(),
                initial.lastName(),
                initial.firstName(),
                initial.middleName(),
                initial.birthDate(),
                List.of(new SubjectService.ContactForm(ContactType.EMAIL, "travin-new@example.ru", true)));

        Consent consent = registration
                .register(
                        UUID.randomUUID().toString(),
                        request(
                                onlyEmail,
                                List.of(new ConsentRegistrationService.ItemDecision(
                                        itemOf("ADVERTISING_EMAIL").getId(), true))))
                .created()
                .get(0);

        var contacts = subjects.get(consent.getSubjectId()).getContacts();
        assertThat(contacts)
                .as("телефон клиента не должен исчезать при регистрации по почте")
                .anyMatch(contact -> contact.getType() == ContactType.PHONE);
        assertThat(contacts).anyMatch(contact -> contact.getType() == ContactType.EMAIL);
        assertThat(contacts).hasSize(3);
    }
}
