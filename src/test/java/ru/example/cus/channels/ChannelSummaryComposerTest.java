package ru.example.cus.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.channels.domain.ChannelEvaluator;
import ru.example.cus.channels.domain.ChannelSummaryComposer;
import ru.example.cus.channels.domain.ConsentSnapshot;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentStatus;

/** FR-6.1: сводка читается вслух перед звонком, поэтому проверяется буквально (Приложение A). */
class ChannelSummaryComposerTest {

    private static ConsentSnapshot consent(String typeCode, Set<CommunicationChannel> channels, ConsentStatus status) {
        return new ConsentSnapshot(
                UUID.randomUUID(), typeCode, channels, status, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void summary_names_allowed_actions_and_explains_a_revocation() {
        String summary = ChannelSummaryComposer.compose(ChannelEvaluator.evaluate(List.of(
                consent(ChannelEvaluator.BASE_CONSENT_TYPE_CODE, Set.of(), ConsentStatus.ACTIVE),
                consent("ADVERTISING_PHONE", Set.of(CommunicationChannel.PHONE_CALL), ConsentStatus.ACTIVE),
                consent("ADVERTISING_EMAIL", Set.of(CommunicationChannel.EMAIL), ConsentStatus.REVOKED))));

        assertThat(summary).isEqualTo("Можно звонить. Реклама по email запрещена: согласие отозвано.");
    }

    @Test
    void missing_base_consent_is_explained_in_one_sentence() {
        String summary = ChannelSummaryComposer.compose(ChannelEvaluator.evaluate(
                List.of(consent("ADVERTISING_PHONE", Set.of(CommunicationChannel.PHONE_CALL), ConsentStatus.ACTIVE))));

        assertThat(summary)
                .isEqualTo("Связываться с клиентом нельзя: нет базового согласия на обработку персональных данных.");
    }

    @Test
    void when_nothing_is_allowed_the_summary_says_so_plainly() {
        String summary = ChannelSummaryComposer.compose(ChannelEvaluator.evaluate(
                List.of(consent(ChannelEvaluator.BASE_CONSENT_TYPE_CODE, Set.of(), ConsentStatus.ACTIVE))));

        assertThat(summary).isEqualTo("Связываться с клиентом нельзя ни по одному каналу.");
    }

    @Test
    void several_allowed_channels_are_listed_together() {
        String summary = ChannelSummaryComposer.compose(ChannelEvaluator.evaluate(List.of(
                consent(ChannelEvaluator.BASE_CONSENT_TYPE_CODE, Set.of(), ConsentStatus.ACTIVE),
                consent("ADVERTISING_PHONE", Set.of(CommunicationChannel.PHONE_CALL), ConsentStatus.ACTIVE),
                consent("ADVERTISING_SMS", Set.of(CommunicationChannel.SMS), ConsentStatus.EXPIRING))));

        assertThat(summary).startsWith("Можно ").contains("звонить").contains("отправлять SMS");
    }
}
