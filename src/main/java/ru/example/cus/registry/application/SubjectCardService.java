package ru.example.cus.registry.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.channels.domain.ChannelDecision;
import ru.example.cus.channels.domain.ChannelEvaluator;
import ru.example.cus.channels.domain.ChannelSummaryComposer;
import ru.example.cus.channels.domain.SubjectConsentPort;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.thirdparty.application.TransferService;
import ru.example.cus.thirdparty.domain.TransferEvaluator;

/**
 * Сборка карточки клиента (FR-5.1, Приложение A, UI-4).
 *
 * <p>Одна сборка на REST-ответ и на экран: если бы интерфейс считал каналы сам, ответы «можно / нельзя»
 * в API и на экране могли бы разойтись, а это ровно то, что запрещает FR-6.3.
 */
@Service
public class SubjectCardService {

    /** @param summaryRu текстовая сводка по каналам для менеджера («Можно звонить. Реклама по email запрещена…») */
    public record SubjectCard(
            Subject subject,
            List<ConsentQueryService.ConsentView> consents,
            List<ChannelDecision> channels,
            String summaryRu,
            List<TransferEvaluator.TransferPermission> transfers,
            Instant generatedAt) {}

    private final SubjectService subjects;
    private final ConsentQueryService consents;
    private final SubjectConsentPort snapshots;
    private final TransferService transfers;
    private final Clock clock;

    public SubjectCardService(
            SubjectService subjects,
            ConsentQueryService consents,
            SubjectConsentPort snapshots,
            TransferService transfers,
            Clock clock) {
        this.subjects = subjects;
        this.consents = consents;
        this.snapshots = snapshots;
        this.transfers = transfers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SubjectCard cardOf(UUID subjectId) {
        return cardOf(subjects.get(subjectId));
    }

    @Transactional(readOnly = true)
    public SubjectCard cardByExternalId(String externalId) {
        return cardOf(subjects.getByExternalId(externalId));
    }

    @Transactional(readOnly = true)
    public SubjectCard cardOf(Subject subject) {
        List<ChannelDecision> decisions = ChannelEvaluator.evaluate(snapshots.currentConsentsOf(subject.getId()));
        return new SubjectCard(
                subject,
                consents.cardConsentsOf(subject.getId()),
                decisions,
                ChannelSummaryComposer.compose(decisions),
                transfers.transfersForCard(subject.getId()),
                clock.instant());
    }
}
