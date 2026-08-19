package ru.example.cus.registry.api;

import java.time.OffsetDateTime;
import java.util.List;
import ru.example.cus.common.api.ChannelView;
import ru.example.cus.common.api.TransferView;

/** Карточка клиента (FR-5.1, Приложение A). */
public record SubjectCardResponse(
        SubjectResponse subject,
        List<ConsentResponse> consents,
        List<ChannelView> channels,
        String channelsSummaryRu,
        List<TransferView> transfers,
        OffsetDateTime generatedAt) {}
