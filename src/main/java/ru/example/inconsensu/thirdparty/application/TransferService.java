package ru.example.inconsensu.thirdparty.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.thirdparty.domain.SubjectTransferPort;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;
import ru.example.inconsensu.thirdparty.domain.TransferEvaluator;
import ru.example.inconsensu.thirdparty.domain.TransferSnapshots;
import ru.example.inconsensu.thirdparty.infrastructure.ThirdPartyRepository;

/** Кому и какие данные субъекта можно передать (§7.7). */
@Service
public class TransferService {

    private final SubjectTransferPort consents;
    private final ThirdPartyRepository thirdParties;
    private final PdnAccessLogService pdnAccessLog;
    private final InConsensuProperties properties;
    private final Clock clock;

    public TransferService(
            SubjectTransferPort consents,
            ThirdPartyRepository thirdParties,
            PdnAccessLogService pdnAccessLog,
            InConsensuProperties properties,
            Clock clock) {
        this.consents = consents;
        this.thirdParties = thirdParties;
        this.pdnAccessLog = pdnAccessLog;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TransferEvaluator.TransferPermission> transfersOf(UUID subjectId) {
        pdnAccessLog.recordSingle("/api/v1/subjects/{id}/transfers", subjectId);
        return evaluate(subjectId);
    }

    /** Внутренний вызов для карточки: обращение уже зафиксировано её собственной записью (FR-10.5). */
    @Transactional(readOnly = true)
    public List<TransferEvaluator.TransferPermission> transfersForCard(UUID subjectId) {
        return evaluate(subjectId);
    }

    @Transactional(readOnly = true)
    public TransferEvaluator.TransferCheck check(UUID subjectId, UUID thirdPartyId, List<String> categories) {
        pdnAccessLog.recordSingle("/api/v1/transfers/check", subjectId);
        ThirdParty recipient = thirdParties.findById(thirdPartyId).orElse(null);
        return TransferEvaluator.check(
                consents.transferConsentsOf(subjectId),
                recipient == null ? null : toRecipient(recipient),
                categories,
                consents.baseConsentUsable(subjectId),
                clock.instant(),
                properties.timezone());
    }

    private List<TransferEvaluator.TransferPermission> evaluate(UUID subjectId) {
        List<TransferSnapshots.TransferConsent> transferConsents = consents.transferConsentsOf(subjectId);
        Map<UUID, TransferSnapshots.Recipient> recipients = new HashMap<>();
        transferConsents.stream()
                .map(TransferSnapshots.TransferConsent::thirdPartyId)
                .distinct()
                .forEach(id -> thirdParties.findById(id).ifPresent(found -> recipients.put(id, toRecipient(found))));

        return TransferEvaluator.evaluate(
                transferConsents,
                recipients,
                consents.baseConsentUsable(subjectId),
                clock.instant(),
                properties.timezone());
    }

    private TransferSnapshots.Recipient toRecipient(ThirdParty thirdParty) {
        return new TransferSnapshots.Recipient(
                thirdParty.getId(),
                thirdParty.getName(),
                thirdParty.getRole().name(),
                List.copyOf(thirdParty.getAllowedPdnCategories()),
                endOfDay(thirdParty.getContractValidUntil()),
                thirdParty.isActive());
    }

    /**
     * Договор действует до конца своего последнего дня.
     *
     * <p>Иначе договор, заканчивающийся сегодня, закрывал бы передачу с полуночи — на день раньше, чем
     * написано в бумаге.
     */
    private java.time.Instant endOfDay(LocalDate date) {
        return date == null
                ? null
                : date.plusDays(1)
                        .atStartOfDay(properties.timezone())
                        .toInstant()
                        .minusMillis(1);
    }
}
