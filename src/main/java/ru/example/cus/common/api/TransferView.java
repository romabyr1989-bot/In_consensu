package ru.example.cus.common.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Разрешённая передача данных третьему лицу (FR-7.2, Приложение A).
 *
 * <p>Как и {@code ChannelView}, живёт в common: одну форму отдают и карточка клиента, и отдельный эндпоинт
 * передач, а видеть DTO друг друга модулям нельзя (§5).
 */
public record TransferView(
        ThirdPartyRef thirdParty,
        List<String> pdnCategories,
        OffsetDateTime validUntil,
        Long daysLeft,
        UUID basisConsentId,
        boolean thirdPartyContractExpired) {

    public record ThirdPartyRef(UUID id, String name, String role) {}
}
