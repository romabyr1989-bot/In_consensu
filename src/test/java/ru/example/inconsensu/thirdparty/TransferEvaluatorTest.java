package ru.example.inconsensu.thirdparty;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.thirdparty.domain.TransferEvaluator;
import ru.example.inconsensu.thirdparty.domain.TransferSnapshots;

/** FR-7.2, FR-7.3: состав передачи — пересечение согласия и договора, срок — минимум из двух. */
class TransferEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final UUID MOMENTO = UUID.randomUUID();

    private static TransferSnapshots.TransferConsent consent(
            List<String> categories, ConsentStatus status, Instant validUntil) {
        return new TransferSnapshots.TransferConsent(UUID.randomUUID(), MOMENTO, categories, status, validUntil);
    }

    private static TransferSnapshots.Recipient recipient(List<String> allowed, Instant contractUntil, boolean active) {
        return new TransferSnapshots.Recipient(MOMENTO, "ООО «Моменто»", "PROCESSOR", allowed, contractUntil, active);
    }

    private static List<TransferEvaluator.TransferPermission> evaluate(
            TransferSnapshots.TransferConsent consent, TransferSnapshots.Recipient recipient) {
        return TransferEvaluator.evaluate(List.of(consent), Map.of(MOMENTO, recipient), true, NOW, MOSCOW);
    }

    @Test
    void allowed_categories_are_the_intersection_of_consent_and_contract() {
        var permissions = evaluate(
                consent(List.of("FIO", "PHONE", "EMAIL"), ConsentStatus.ACTIVE, NOW.plus(60, ChronoUnit.DAYS)),
                recipient(List.of("FIO", "PHONE", "POSTAL_ADDRESS"), NOW.plus(200, ChronoUnit.DAYS), true));

        // EMAIL есть в согласии, но не в договоре; POSTAL_ADDRESS — наоборот. Передавать можно только общее.
        assertThat(permissions).hasSize(1);
        assertThat(permissions.get(0).allowedCategories()).containsExactly("FIO", "PHONE");
    }

    @Test
    void validity_is_the_earlier_of_the_consent_and_the_contract() {
        Instant consentEnd = NOW.plus(60, ChronoUnit.DAYS);
        Instant contractEnd = NOW.plus(20, ChronoUnit.DAYS);

        var byContract = evaluate(
                consent(List.of("FIO"), ConsentStatus.ACTIVE, consentEnd),
                recipient(List.of("FIO"), contractEnd, true));
        var byConsent = evaluate(
                consent(List.of("FIO"), ConsentStatus.ACTIVE, contractEnd),
                recipient(List.of("FIO"), consentEnd, true));

        assertThat(byContract.get(0).validUntil()).isEqualTo(contractEnd);
        assertThat(byConsent.get(0).validUntil()).isEqualTo(contractEnd);
        assertThat(byContract.get(0).daysLeft()).isEqualTo(20);
    }

    @Test
    void open_ended_consent_is_limited_by_the_contract() {
        Instant contractEnd = NOW.plus(30, ChronoUnit.DAYS);

        var permissions = evaluate(
                consent(List.of("FIO"), ConsentStatus.ACTIVE, null), recipient(List.of("FIO"), contractEnd, true));

        assertThat(permissions.get(0).validUntil()).isEqualTo(contractEnd);
    }

    @ParameterizedTest
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REVOKED", "EXPIRED", "SUPERSEDED"})
    void dead_consent_gives_no_permission(ConsentStatus status) {
        assertThat(evaluate(
                        consent(List.of("FIO"), status, NOW.plus(60, ChronoUnit.DAYS)),
                        recipient(List.of("FIO"), NOW.plus(60, ChronoUnit.DAYS), true)))
                .isEmpty();
    }

    @Test
    void deactivated_recipient_gives_no_permission() {
        assertThat(evaluate(
                        consent(List.of("FIO"), ConsentStatus.ACTIVE, null), recipient(List.of("FIO"), null, false)))
                .isEmpty();
    }

    @Test
    void expired_contract_is_flagged_so_the_card_can_warn_about_it() {
        var permissions = evaluate(
                consent(List.of("FIO"), ConsentStatus.ACTIVE, null),
                recipient(List.of("FIO"), NOW.minus(1, ChronoUnit.DAYS), true));

        assertThat(permissions).hasSize(1);
        assertThat(permissions.get(0).contractExpired()).isTrue();
    }

    @Test
    void empty_intersection_means_nothing_can_be_transferred() {
        assertThat(evaluate(
                        consent(List.of("EMAIL"), ConsentStatus.ACTIVE, null), recipient(List.of("FIO"), null, true)))
                .isEmpty();
    }

    @Test
    void check_answers_which_of_the_requested_categories_are_allowed() {
        var result = TransferEvaluator.check(
                List.of(consent(List.of("FIO", "PHONE"), ConsentStatus.ACTIVE, null)),
                recipient(List.of("FIO", "PHONE", "EMAIL"), null, true),
                List.of("FIO", "EMAIL"),
                true,
                NOW,
                MOSCOW);

        assertThat(result.allowed()).isFalse();
        assertThat(result.allowedCategories()).containsExactly("FIO");
        assertThat(result.deniedCategories()).containsExactly("EMAIL");
        assertThat(result.reason()).contains("не покрыта");
    }

    @Test
    void check_confirms_a_fully_covered_request() {
        var result = TransferEvaluator.check(
                List.of(consent(List.of("FIO", "PHONE"), ConsentStatus.ACTIVE, null)),
                recipient(List.of("FIO", "PHONE"), null, true),
                List.of("FIO"),
                true,
                NOW,
                MOSCOW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.deniedCategories()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void check_explains_an_expired_contract_and_a_missing_consent() {
        var expiredContract = TransferEvaluator.check(
                List.of(consent(List.of("FIO"), ConsentStatus.ACTIVE, null)),
                recipient(List.of("FIO"), NOW.minus(1, ChronoUnit.DAYS), true),
                List.of("FIO"),
                true,
                NOW,
                MOSCOW);
        var noConsent = TransferEvaluator.check(
                List.of(), recipient(List.of("FIO"), null, true), List.of("FIO"), true, NOW, MOSCOW);

        assertThat(expiredContract.allowed()).isFalse();
        assertThat(expiredContract.reason()).contains("Договор");
        assertThat(noConsent.reason()).contains("Нет действующего согласия");
    }

    /** §8.3 п.3: без живого базового согласия передача запрещена, как и канал. */
    @Test
    void transfer_is_denied_without_a_live_base_consent() {
        var permissions = TransferEvaluator.evaluate(
                List.of(consent(List.of("FIO"), ConsentStatus.ACTIVE, null)),
                Map.of(MOMENTO, recipient(List.of("FIO"), null, true)),
                false,
                NOW,
                MOSCOW);
        var check = TransferEvaluator.check(
                List.of(consent(List.of("FIO"), ConsentStatus.ACTIVE, null)),
                recipient(List.of("FIO"), null, true),
                List.of("FIO"),
                false,
                NOW,
                MOSCOW);

        assertThat(permissions).isEmpty();
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).contains("обработку ПДн");
    }
}
