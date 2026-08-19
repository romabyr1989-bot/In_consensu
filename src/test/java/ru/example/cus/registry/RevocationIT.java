package ru.example.cus.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.channels.application.ChannelService;
import ru.example.cus.common.domain.ChannelDenyReason;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ConsentStatus;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.registry.application.ConsentQueryService;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.RevocationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.TestForms;

/** Приёмка этапа 5: отзыв необратим, действует немедленно и гасит зависимые согласия (FR-8.2 … FR-8.5). */
class RevocationIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private RevocationService revocation;

    @Autowired
    private ConsentQueryService consents;

    @Autowired
    private ChannelService channels;

    @Autowired
    private SubjectService subjects;

    @Autowired
    private TestForms testForms;

    private ConsentForm form;
    private UUID subjectId;

    @BeforeEach
    void setUp() {
        form = testForms.publish(List.of(
                new ConsentFormService.ItemForm(
                        "PDN_PROCESSING",
                        "Согласие на обработку персональных данных",
                        List.of("рассмотрение заявки"),
                        List.of("FIO", "PHONE", "EMAIL"),
                        null,
                        null,
                        true),
                new ConsentFormService.ItemForm(
                        "ADVERTISING_EMAIL",
                        "Реклама по электронной почте",
                        List.of("информирование"),
                        List.of("EMAIL"),
                        null,
                        null,
                        false),
                new ConsentFormService.ItemForm(
                        "ADVERTISING_PHONE",
                        "Реклама по телефону",
                        List.of("информирование"),
                        List.of("PHONE"),
                        null,
                        null,
                        false)));
        subjectId = register();
    }

    private UUID register() {
        var subject = new SubjectService.SubjectForm(
                "CRM-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-41", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, "travin@example.ru", true)));

        return registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                subject,
                                form.getId(),
                                form.getItems().stream()
                                        .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                                        .toList(),
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "заявка",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000041",
                                        "otpVerifiedAt", Instant.now().toString(),
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0)
                .getSubjectId();
    }

    private UUID consentIdOf(String typeCode) {
        return consents.effectiveConsentsOf(subjectId).stream()
                .filter(view -> form.getItems().stream()
                        .anyMatch(item -> item.getId().equals(view.consent().getFormItemId())
                                && item.getConsentType().getCode().equals(typeCode)))
                .findFirst()
                .orElseThrow()
                .consent()
                .getId();
    }

    private boolean channelAllowed(CommunicationChannel channel) {
        return channels.channelsOf(subjectId).decisions().stream()
                .filter(decision -> decision.channel() == channel)
                .findFirst()
                .orElseThrow()
                .allowed();
    }

    @Test
    void revocation_closes_the_channel_immediately_within_the_same_test() {
        assertThat(channelAllowed(CommunicationChannel.EMAIL)).isTrue();

        revocation.revoke(
                consentIdOf("ADVERTISING_EMAIL"),
                "Клиент отказался от рассылок",
                RevocationSource.CALL_CENTER,
                "OBR-1",
                Map.of());

        // Никаких задержек и кэшей между отзывом и проверкой канала (FR-6.3, FR-8.3).
        assertThat(channelAllowed(CommunicationChannel.EMAIL)).isFalse();
        assertThat(channelAllowed(CommunicationChannel.PHONE_CALL)).isTrue();

        var reason = channels.channelsOf(subjectId).decisions().stream()
                .filter(decision -> decision.channel() == CommunicationChannel.EMAIL)
                .findFirst()
                .orElseThrow()
                .reason();
        assertThat(reason).isEqualTo(ChannelDenyReason.REVOKED);
    }

    @Test
    void revoking_the_base_consent_cascades_to_every_dependent_type() {
        var result = revocation.revoke(
                consentIdOf("PDN_PROCESSING"),
                "Клиент отозвал согласие на обработку",
                RevocationSource.WRITTEN_REQUEST,
                "OBR-2",
                Map.of("documentRef", "scan://2026/17"));

        // FR-8.4: реклама по email и по телефону зависят от базового типа и гаснут вместе с ним.
        assertThat(result.cascaded()).hasSize(2);
        assertThat(consents.effectiveConsentsOf(subjectId)).isEmpty();
        assertThat(channels.channelsOf(subjectId).decisions())
                .allSatisfy(decision -> assertThat(decision.allowed()).isFalse());
        assertThat(result.cascaded())
                .allSatisfy(consent -> assertThat(consent.getRevocationSource()).isEqualTo(RevocationSource.CASCADE));
    }

    @Test
    void revocation_carries_the_deadline_to_stop_processing() {
        var result = revocation.revoke(
                consentIdOf("PDN_PROCESSING"), "Отзыв", RevocationSource.PERSONAL_ACCOUNT, "OBR-3", Map.of());

        // ч. 5 ст. 21 152-ФЗ: 30 дней на прекращение обработки (FR-8.5).
        assertThat(result.processingStopDeadline())
                .isEqualTo(result.revokedAt().plus(RevocationService.PROCESSING_STOP_PERIOD));
        assertThat(ChronoUnit.DAYS.between(result.revokedAt(), result.processingStopDeadline()))
                .isEqualTo(30);
    }

    @Test
    void repeated_revocation_changes_nothing_and_is_not_an_error() {
        UUID consentId = consentIdOf("ADVERTISING_EMAIL");
        var first = revocation.revoke(consentId, "Отзыв", RevocationSource.CALL_CENTER, "OBR-4", Map.of());

        var second = revocation.revoke(consentId, "Отзыв ещё раз", RevocationSource.EMAIL_REQUEST, "OBR-5", Map.of());

        assertThat(second.revokedAt()).isEqualTo(first.revokedAt());
        assertThat(second.cascaded()).isEmpty();
        assertThat(consents.get(consentId).consent().getRevocationSource()).isEqualTo(RevocationSource.CALL_CENTER);
    }

    @Test
    void revocation_is_irreversible_and_a_new_consent_is_a_new_record() {
        UUID revokedId = consentIdOf("ADVERTISING_EMAIL");
        revocation.revoke(revokedId, "Отзыв", RevocationSource.CALL_CENTER, "OBR-6", Map.of());

        UUID newConsentId = registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                externalIdOfSubject(),
                                null,
                                form.getId(),
                                List.of(new ConsentRegistrationService.ItemDecision(
                                        form.getItems().stream()
                                                .filter(item -> item.getConsentType()
                                                        .getCode()
                                                        .equals("ADVERTISING_EMAIL"))
                                                .findFirst()
                                                .orElseThrow()
                                                .getId(),
                                        true)),
                                Instant.now(),
                                ConsentSource.PERSONAL_ACCOUNT_REGISTRATION,
                                "ЛК",
                                SignatureType.SIMPLE_ES_LK,
                                Map.of(
                                        "accountId", "lk-1",
                                        "authMethod", "password",
                                        "actionAt", Instant.now().toString(),
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0)
                .getId();

        assertThat(newConsentId).isNotEqualTo(revokedId);
        assertThat(consents.get(revokedId).status()).isEqualTo(ConsentStatus.REVOKED);
        assertThat(channelAllowed(CommunicationChannel.EMAIL)).isTrue();
    }

    @Test
    void revoke_all_advertising_stops_every_advertising_type_at_once() {
        var results = revocation.revokeAllAdvertising(
                subjectId, "Требование прекратить рекламу", RevocationSource.PERSONAL_ACCOUNT, "OBR-7");

        assertThat(results).hasSize(2);
        assertThat(channelAllowed(CommunicationChannel.EMAIL)).isFalse();
        assertThat(channelAllowed(CommunicationChannel.PHONE_CALL)).isFalse();
        // Базовое согласие на обработку остаётся: клиент отказался от рекламы, а не от обслуживания.
        assertThat(consents.effectiveConsentsOf(subjectId)).hasSize(1);
    }

    @Test
    void revocation_without_a_reason_is_rejected() {
        assertThatThrownBy(() -> revocation.revoke(
                        consentIdOf("ADVERTISING_EMAIL"), "  ", RevocationSource.CALL_CENTER, "OBR-8", Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("причину");
    }

    @Test
    void cascade_preview_shows_what_will_be_revoked_before_confirmation() {
        var preview = revocation.previewCascade(consentIdOf("PDN_PROCESSING"));

        // UI-5 показывает этот список в диалоге подтверждения до необратимого действия.
        assertThat(preview).hasSize(2);
        assertThat(preview).extracting(Consent::getRevokedAt).containsOnlyNulls();
    }

    private String externalIdOfSubject() {
        return subjects.get(subjectId).getExternalId();
    }
}
