package ru.example.inconsensu.channels.domain;

import java.time.Instant;
import java.util.UUID;
import ru.example.inconsensu.common.domain.ChannelDenyReason;
import ru.example.inconsensu.common.domain.CommunicationChannel;

/** Ответ «можно / нельзя» по одному каналу с основанием или причиной запрета (FR-6.1). */
public record ChannelDecision(CommunicationChannel channel, boolean allowed, Basis basis, ChannelDenyReason reason) {

    /** Согласие, на котором держится разрешение. */
    public record Basis(UUID consentId, String typeCode, Instant validUntil) {}

    public static ChannelDecision allowed(CommunicationChannel channel, ConsentSnapshot consent) {
        return new ChannelDecision(
                channel, true, new Basis(consent.consentId(), consent.typeCode(), consent.validUntil()), null);
    }

    public static ChannelDecision denied(CommunicationChannel channel, ChannelDenyReason reason) {
        return new ChannelDecision(channel, false, null, reason);
    }
}
