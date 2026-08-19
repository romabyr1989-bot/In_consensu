package ru.example.inconsensu.channels.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentStatus;

/**
 * Согласие в том виде, в каком его видит расчёт каналов и передач.
 *
 * <p>Домен не ходит в репозитории (§5): application-слой достаёт согласия субъекта и превращает их в набор
 * значений. Благодаря этому все комбинации правила FR-6.2 проверяются обычным юнит-тестом.
 *
 * <p>Здесь и отозванные, и истёкшие согласия: чтобы ответить «нельзя, потому что отозвано», недостаточно
 * видеть только действующие (FR-6.1).
 */
public record ConsentSnapshot(
        UUID consentId,
        String typeCode,
        Set<CommunicationChannel> channels,
        ConsentStatus status,
        Instant grantedAt,
        Instant validUntil) {

    /** Согласие даёт право действовать: только ACTIVE и EXPIRING (§8.3). */
    public boolean isUsable() {
        return status == ConsentStatus.ACTIVE || status == ConsentStatus.EXPIRING;
    }

    public boolean covers(CommunicationChannel channel) {
        return channels != null && channels.contains(channel);
    }
}
