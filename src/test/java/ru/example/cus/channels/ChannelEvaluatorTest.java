package ru.example.cus.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.example.cus.channels.domain.ChannelDecision;
import ru.example.cus.channels.domain.ChannelEvaluator;
import ru.example.cus.channels.domain.ConsentSnapshot;
import ru.example.cus.common.domain.ChannelDenyReason;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentStatus;

/** FR-6.2: канал разрешён только при живом профильном И живом базовом согласии. Всё остальное — запрет. */
class ChannelEvaluatorTest {

    private static final Instant GRANTED = Instant.parse("2026-01-01T00:00:00Z");

    private static ConsentSnapshot base(ConsentStatus status) {
        return new ConsentSnapshot(
                UUID.randomUUID(), ChannelEvaluator.BASE_CONSENT_TYPE_CODE, Set.of(), status, GRANTED, null);
    }

    private static ConsentSnapshot email(ConsentStatus status) {
        return new ConsentSnapshot(
                UUID.randomUUID(), "ADVERTISING_EMAIL", Set.of(CommunicationChannel.EMAIL), status, GRANTED, null);
    }

    private static ChannelDecision decisionFor(CommunicationChannel channel, List<ConsentSnapshot> consents) {
        return ChannelEvaluator.evaluate(consents).stream()
                .filter(decision -> decision.channel() == channel)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void channel_is_allowed_when_both_the_profile_and_the_base_consent_are_alive() {
        var decision = decisionFor(
                CommunicationChannel.EMAIL, List.of(base(ConsentStatus.ACTIVE), email(ConsentStatus.ACTIVE)));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.basis().typeCode()).isEqualTo("ADVERTISING_EMAIL");
        assertThat(decision.reason()).isNull();
    }

    @Test
    void expiring_consent_still_allows_the_channel() {
        // EXPIRING — это предупреждение о сроке, а не запрет: клиент пока согласен (§8.3).
        assertThat(decisionFor(
                                CommunicationChannel.EMAIL,
                                List.of(base(ConsentStatus.EXPIRING), email(ConsentStatus.EXPIRING)))
                        .allowed())
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REVOKED", "EXPIRED", "SUPERSEDED"})
    void dead_profile_consent_closes_the_channel(ConsentStatus deadStatus) {
        var decision = decisionFor(CommunicationChannel.EMAIL, List.of(base(ConsentStatus.ACTIVE), email(deadStatus)));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.basis()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REVOKED", "EXPIRED", "SUPERSEDED"})
    void dead_base_consent_closes_every_channel(ConsentStatus deadStatus) {
        var decisions = ChannelEvaluator.evaluate(List.of(base(deadStatus), email(ConsentStatus.ACTIVE)));

        assertThat(decisions).allSatisfy(decision -> {
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(ChannelDenyReason.BASE_CONSENT_MISSING);
        });
    }

    @Test
    void revocation_is_reported_rather_than_a_generic_absence() {
        var decision = decisionFor(
                CommunicationChannel.EMAIL, List.of(base(ConsentStatus.ACTIVE), email(ConsentStatus.REVOKED)));

        assertThat(decision.reason()).isEqualTo(ChannelDenyReason.REVOKED);
    }

    @Test
    void expiry_is_reported_when_there_was_no_revocation() {
        var decision = decisionFor(
                CommunicationChannel.EMAIL, List.of(base(ConsentStatus.ACTIVE), email(ConsentStatus.EXPIRED)));

        assertThat(decision.reason()).isEqualTo(ChannelDenyReason.EXPIRED);
    }

    @Test
    void revocation_outweighs_expiry_in_the_explanation() {
        var decision = decisionFor(
                CommunicationChannel.EMAIL,
                List.of(base(ConsentStatus.ACTIVE), email(ConsentStatus.EXPIRED), email(ConsentStatus.REVOKED)));

        assertThat(decision.reason()).isEqualTo(ChannelDenyReason.REVOKED);
    }

    @Test
    void channel_without_any_consent_is_denied_by_default() {
        var decisions = ChannelEvaluator.evaluate(List.of(base(ConsentStatus.ACTIVE)));

        // Deny by default, включая почтовую рассылку (FR-6.2).
        assertThat(decisions).hasSize(CommunicationChannel.values().length);
        assertThat(decisions)
                .allSatisfy(decision -> assertThat(decision.allowed()).isFalse());
        assertThat(decisionFor(CommunicationChannel.POSTAL_MAIL, List.of(base(ConsentStatus.ACTIVE)))
                        .reason())
                .isEqualTo(ChannelDenyReason.NO_CONSENT);
    }

    @Test
    void subject_without_any_consent_at_all_is_closed_for_everything() {
        var decisions = ChannelEvaluator.evaluate(List.of());

        assertThat(decisions).allSatisfy(decision -> assertThat(decision.reason())
                .isEqualTo(ChannelDenyReason.BASE_CONSENT_MISSING));
    }

    @Test
    void consent_of_another_type_does_not_open_a_foreign_channel() {
        var decision =
                decisionFor(CommunicationChannel.SMS, List.of(base(ConsentStatus.ACTIVE), email(ConsentStatus.ACTIVE)));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(ChannelDenyReason.NO_CONSENT);
    }

    @Test
    void the_latest_usable_consent_becomes_the_basis() {
        ConsentSnapshot older = new ConsentSnapshot(
                UUID.randomUUID(),
                "ADVERTISING_EMAIL",
                Set.of(CommunicationChannel.EMAIL),
                ConsentStatus.ACTIVE,
                GRANTED,
                null);
        ConsentSnapshot newer = new ConsentSnapshot(
                UUID.randomUUID(),
                "ADVERTISING_EMAIL",
                Set.of(CommunicationChannel.EMAIL),
                ConsentStatus.ACTIVE,
                GRANTED.plusSeconds(3600),
                null);

        var decision = decisionFor(CommunicationChannel.EMAIL, List.of(base(ConsentStatus.ACTIVE), older, newer));

        assertThat(decision.basis().consentId()).isEqualTo(newer.consentId());
    }
}
