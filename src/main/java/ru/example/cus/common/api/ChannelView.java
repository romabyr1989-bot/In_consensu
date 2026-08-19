package ru.example.cus.common.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ответ по одному каналу коммуникации (FR-6.1, Приложение A).
 *
 * <p>Живёт в common, потому что одну и ту же форму отдают и карточка клиента, и отдельный эндпоинт каналов:
 * два модуля не должны видеть DTO друг друга (§5), а контракт для клиента обязан остаться одним.
 */
public record ChannelView(
        String channel, String channelRu, boolean allowed, Basis basis, String reason, String reasonRu) {

    public record Basis(UUID consentId, String typeCode, OffsetDateTime validUntil) {}
}
