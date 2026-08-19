package ru.example.cus.channels.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.PdnAccessLogService;
import ru.example.cus.channels.domain.ChannelDecision;
import ru.example.cus.channels.domain.ChannelEvaluator;
import ru.example.cus.channels.domain.ChannelSummaryComposer;
import ru.example.cus.channels.domain.ConsentSnapshot;
import ru.example.cus.channels.domain.SubjectConsentPort;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;

/**
 * Ответ «можно / нельзя» по каналам коммуникации (§7.6).
 *
 * <p>Результат не кэшируется: отзыв согласия вступает в силу немедленно (FR-6.3), и закэшированное «можно»
 * означало бы звонок клиенту, который только что запретил звонить.
 */
@Service
public class ChannelService {

    /** FR-6.4: предел массовой проверки за один вызов. */
    public static final int MAX_BULK_IDENTIFIERS = 10_000;

    public record SubjectChannels(UUID subjectId, List<ChannelDecision> decisions, String summaryRu) {}

    public record BulkResult(
            CommunicationChannel channel,
            List<String> allowed,
            Map<String, String> deniedReasons,
            List<String> unknownIdentifiers) {}

    private final SubjectConsentPort consents;
    private final PdnAccessLogService pdnAccessLog;

    public ChannelService(SubjectConsentPort consents, PdnAccessLogService pdnAccessLog) {
        this.consents = consents;
        this.pdnAccessLog = pdnAccessLog;
    }

    @Transactional(readOnly = true)
    public SubjectChannels channelsOf(UUID subjectId) {
        pdnAccessLog.recordSingle("/api/v1/subjects/{id}/channels", subjectId);
        return evaluate(subjectId, consents.currentConsentsOf(subjectId));
    }

    /**
     * FR-6.4: канал плюс список до 10 000 идентификаторов.
     *
     * <p>В журнал доступа к ПДн пишется одна агрегированная запись на вызов, а не запись на субъекта: иначе
     * одна рассылка забила бы журнал десятью тысячами строк и сделала его нечитаемым.
     */
    @Transactional(readOnly = true)
    public BulkResult check(CommunicationChannel channel, List<String> identifiers, boolean includeReasons) {
        if (identifiers == null || identifiers.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Список идентификаторов пуст");
        }
        if (identifiers.size() > MAX_BULK_IDENTIFIERS) {
            throw new ApiException(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    "За один вызов проверяется не более " + MAX_BULK_IDENTIFIERS + " идентификаторов (FR-6.4)");
        }

        SubjectConsentPort.ResolvedSubjects resolved = consents.resolve(identifiers);
        pdnAccessLog.recordBulk("/api/v1/channels/check", identifiers.size());

        Map<UUID, List<ConsentSnapshot>> bySubject =
                consents.currentConsentsOf(resolved.byIdentifier().values());

        List<String> allowed = new ArrayList<>();
        Map<String, String> denied = new LinkedHashMap<>();

        resolved.byIdentifier().forEach((identifier, subjectId) -> {
            List<ConsentSnapshot> snapshots = bySubject.getOrDefault(subjectId, List.of());
            ChannelDecision decision =
                    ChannelEvaluator.decide(channel, snapshots, ChannelEvaluator.hasUsableBaseConsent(snapshots));
            if (decision.allowed()) {
                allowed.add(identifier);
            } else if (includeReasons) {
                denied.put(identifier, decision.reason().name());
            }
        });

        return new BulkResult(channel, allowed, denied, resolved.unknown());
    }

    private SubjectChannels evaluate(UUID subjectId, List<ConsentSnapshot> snapshots) {
        List<ChannelDecision> decisions = ChannelEvaluator.evaluate(snapshots);
        return new SubjectChannels(subjectId, decisions, ChannelSummaryComposer.compose(decisions));
    }
}
