package ru.example.inconsensu.channels.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import ru.example.inconsensu.common.domain.ChannelDenyReason;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentStatus;

/**
 * Правило разрешения канала коммуникации (FR-6.2).
 *
 * <p>Deny by default: канал разрешён тогда и только тогда, когда у субъекта есть действующее или истекающее
 * согласие типа, открывающего этот канал, и одновременно живо базовое согласие на обработку ПДн. Всё
 * остальное — запрет, включая почтовую рассылку.
 *
 * <p>Отсутствие базового согласия объясняется отдельной причиной и перекрывает остальные: без обработки ПДн
 * рекламное согласие юридически бессмысленно, и сотруднику важно увидеть именно первопричину (UI-4).
 */
public final class ChannelEvaluator {

    /** Код базового типа согласия, без которого запрещено всё (§8.3, Приложение B). */
    public static final String BASE_CONSENT_TYPE_CODE = "PDN_PROCESSING";

    private ChannelEvaluator() {}

    public static List<ChannelDecision> evaluate(List<ConsentSnapshot> consents) {
        List<ChannelDecision> decisions = new ArrayList<>();
        boolean baseAlive = hasUsableBaseConsent(consents);

        for (CommunicationChannel channel : CommunicationChannel.values()) {
            decisions.add(decide(channel, consents, baseAlive));
        }
        return decisions;
    }

    public static ChannelDecision decide(
            CommunicationChannel channel, List<ConsentSnapshot> consents, boolean baseAlive) {
        Optional<ConsentSnapshot> usable = consents.stream()
                .filter(consent -> consent.covers(channel))
                .filter(ConsentSnapshot::isUsable)
                .max(Comparator.comparing(ConsentSnapshot::grantedAt));

        if (usable.isPresent() && baseAlive) {
            return ChannelDecision.allowed(channel, usable.get());
        }
        if (!baseAlive) {
            return ChannelDecision.denied(channel, ChannelDenyReason.BASE_CONSENT_MISSING);
        }
        Denial denial = reasonFor(channel, consents);
        return ChannelDecision.denied(channel, denial.reason(), denial.blocking());
    }

    public static boolean hasUsableBaseConsent(List<ConsentSnapshot> consents) {
        return consents.stream()
                .filter(consent -> BASE_CONSENT_TYPE_CODE.equals(consent.typeCode()))
                .anyMatch(ConsentSnapshot::isUsable);
    }

    /**
     * Почему канал закрыт.
     *
     * <p>Отзыв важнее истечения: если клиент отозвал согласие, сотрудник должен увидеть именно это, а не
     * «истекло» по давно прошедшему сроку того же согласия.
     */
    private static Denial reasonFor(CommunicationChannel channel, List<ConsentSnapshot> consents) {
        List<ConsentSnapshot> matching =
                consents.stream().filter(consent -> consent.covers(channel)).toList();

        if (matching.isEmpty()) {
            return new Denial(ChannelDenyReason.NO_CONSENT, null);
        }
        Optional<ConsentSnapshot> revoked = latest(matching, ConsentStatus.REVOKED);
        if (revoked.isPresent()) {
            return new Denial(ChannelDenyReason.REVOKED, revoked.get());
        }
        Optional<ConsentSnapshot> expired = latest(matching, ConsentStatus.EXPIRED);
        if (expired.isPresent()) {
            return new Denial(ChannelDenyReason.EXPIRED, expired.get());
        }
        return new Denial(ChannelDenyReason.NO_CONSENT, null);
    }

    /** Из нескольких согласий одного статуса берётся последнее: его дату и увидит сотрудник. */
    private static Optional<ConsentSnapshot> latest(List<ConsentSnapshot> consents, ConsentStatus status) {
        return consents.stream()
                .filter(consent -> consent.status() == status)
                .max(Comparator.comparing(ConsentSnapshot::grantedAt));
    }

    /** @param blocking согласие, из-за которого канал закрыт; {@code null}, если согласия нет вовсе */
    private record Denial(ChannelDenyReason reason, ConsentSnapshot blocking) {}
}
