package ru.example.inconsensu.registry.api;

import java.time.OffsetDateTime;
import java.util.List;
import ru.example.inconsensu.common.api.ChannelView;
import ru.example.inconsensu.common.api.TransferView;

/** Карточка клиента (FR-5.1, Приложение A). */
public record SubjectCardResponse(
        SubjectResponse subject,
        List<ConsentResponse> consents,
        List<ChannelView> channels,
        String channelsSummaryRu,
        List<TransferView> transfers,
        OffsetDateTime generatedAt) {}
