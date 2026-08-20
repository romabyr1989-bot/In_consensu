package ru.example.inconsensu.channels.domain;

import java.time.Instant;
import java.util.UUID;
import ru.example.inconsensu.common.domain.ChannelDenyReason;
import ru.example.inconsensu.common.domain.CommunicationChannel;

/** Ответ «можно / нельзя» по одному каналу с основанием или причиной запрета (FR-6.1). */
public record ChannelDecision(
        CommunicationChannel channel,
        boolean allowed,
        Basis basis,
        ChannelDenyReason reason,
        ConsentSnapshot blocking) {

    /** Согласие, на котором держится разрешение. */
    public record Basis(UUID consentId, String typeCode, Instant validUntil) {}

    public static ChannelDecision allowed(CommunicationChannel channel, ConsentSnapshot consent) {
        return new ChannelDecision(
                channel, true, new Basis(consent.consentId(), consent.typeCode(), consent.validUntil()), null, null);
    }

    public static ChannelDecision denied(CommunicationChannel channel, ChannelDenyReason reason) {
        return denied(channel, reason, null);
    }

    /**
     * Запрет с указанием согласия, которое его вызвало.
     *
     * <p>Снимок нужен экрану: UI-4 требует не только причину, но и дату — «согласие отозвано 02.06.2026».
     */
    public static ChannelDecision denied(
            CommunicationChannel channel, ChannelDenyReason reason, ConsentSnapshot blocking) {
        return new ChannelDecision(channel, false, null, reason, blocking);
    }
}
