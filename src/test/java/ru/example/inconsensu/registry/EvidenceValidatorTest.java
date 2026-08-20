package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.registry.domain.EvidenceValidator;

/** FR-4.2: обязательный состав доказательств по способу подписания (таблица §7.4). */
class EvidenceValidatorTest {

    @Test
    void sms_signature_requires_the_phone_the_moment_and_the_proof_of_the_code() {
        Map<String, Object> complete = Map.of(
                "phone", "+79160000041",
                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                "otpHash", "abc",
                "ip", "10.0.0.1",
                "userAgent", "Mozilla");

        assertThat(EvidenceValidator.missingFields(SignatureType.SIMPLE_ES_SMS, complete))
                .isEmpty();
    }

    @Test
    void sms_signature_accepts_a_provider_reference_instead_of_the_code_hash() {
        Map<String, Object> withProvider = Map.of(
                "phone", "+79160000041",
                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                "providerRef", "sms-gate-42",
                "ip", "10.0.0.1",
                "userAgent", "Mozilla");

        assertThat(EvidenceValidator.missingFields(SignatureType.SIMPLE_ES_SMS, withProvider))
                .isEmpty();
    }

    @Test
    void sms_signature_without_any_proof_of_the_code_is_incomplete() {
        Map<String, Object> noProof = Map.of(
                "phone", "+79160000041",
                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                "ip", "10.0.0.1",
                "userAgent", "Mozilla");

        assertThat(EvidenceValidator.missingFields(SignatureType.SIMPLE_ES_SMS, noProof))
                .containsExactly("otpHash|providerRef");
    }

    @Test
    void personal_account_handwritten_and_ukep_have_their_own_required_sets() {
        assertThat(EvidenceValidator.missingFields(SignatureType.SIMPLE_ES_LK, Map.of()))
                .containsExactly("accountId", "authMethod", "actionAt", "ip", "userAgent");
        assertThat(EvidenceValidator.missingFields(SignatureType.HANDWRITTEN, Map.of()))
                .containsExactly("documentRef", "documentDate", "receivedByUserId");
        assertThat(EvidenceValidator.missingFields(SignatureType.UKEP, Map.of()))
                .containsExactly("signatureRef", "certificateSerial", "signedAt");
    }

    @Test
    void imported_consent_needs_a_job_and_a_ground_document_or_a_note() {
        assertThat(EvidenceValidator.missingFields(SignatureType.IMPORTED_LEGACY, Map.of("importJobId", "job-1")))
                .containsExactly("documentRef|note");
        assertThat(EvidenceValidator.missingFields(
                        SignatureType.IMPORTED_LEGACY, Map.of("importJobId", "job-1", "note", "договор 2019 года")))
                .isEmpty();
    }

    @Test
    void blank_string_counts_as_a_missing_field() {
        assertThat(EvidenceValidator.missingFields(
                        SignatureType.UKEP, Map.of("signatureRef", "   ", "certificateSerial", "x", "signedAt", "y")))
                .containsExactly("signatureRef");
    }

    @Test
    void sensitive_values_are_masked_before_they_reach_a_log() {
        Map<String, Object> masked = EvidenceValidator.withoutSensitiveValues(
                Map.of("phone", "+79160000041", "otpHash", "secret", "accountId", "lk-1"));

        assertThat(masked).containsEntry("phone", "***").containsEntry("otpHash", "***");
        assertThat(masked).containsEntry("accountId", "lk-1");
    }

    /**
     * FR-4.2: телефон SMS-подписи задан в E.164.
     *
     * <p>Значение в произвольном виде формально заполнено, но сопоставить его с абонентом нельзя — как
     * доказательство подписания оно не работает. Раньше формат не проверялся вовсе.
     */
    @Test
    void phone_of_an_sms_signature_must_be_in_e164() {
        assertThat(EvidenceValidator.malformedFields(SignatureType.SIMPLE_ES_SMS, evidenceWithPhone("+79160000041")))
                .isEmpty();

        assertThat(EvidenceValidator.malformedFields(SignatureType.SIMPLE_ES_SMS, evidenceWithPhone("8 916 000-00-41")))
                .containsExactly("phone");
        assertThat(EvidenceValidator.malformedFields(SignatureType.SIMPLE_ES_SMS, evidenceWithPhone("телефон клиента")))
                .containsExactly("phone");
    }

    /** Пустое поле — это отсутствующее доказательство, а не неверный формат: сообщения разные. */
    @Test
    void a_missing_phone_is_not_reported_as_malformed() {
        assertThat(EvidenceValidator.malformedFields(SignatureType.SIMPLE_ES_SMS, Map.of()))
                .isEmpty();
        assertThat(EvidenceValidator.missingFields(SignatureType.SIMPLE_ES_SMS, Map.of()))
                .contains("phone");
    }

    private static Map<String, Object> evidenceWithPhone(String phone) {
        return Map.of(
                "phone", phone,
                "otpVerifiedAt", "2026-08-20T10:00:00Z",
                "otpHash", "hash",
                "ip", "10.0.0.1",
                "userAgent", "Mozilla");
    }
}
