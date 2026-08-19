package ru.example.cus.registry.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditIntegrityService;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.audit.application.PdnAccessLogService;
import ru.example.cus.audit.domain.AuditEvent;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.registry.domain.Consent;

/**
 * Досье согласия: чем именно оператор докажет наличие согласия (FR-10.3, ч. 3 ст. 9 152-ФЗ).
 *
 * <p>Контрольная сумма пересчитывается заново и сравнивается с сохранённой в согласии: если текст формы в
 * базе кто-то поправил в обход правил, расхождение станет видно, а не растворится.
 */
@Service
public class ConsentEvidenceService {

    public record Dossier(
            Consent consent,
            ConsentForm form,
            String formText,
            String storedChecksum,
            String recalculatedChecksum,
            boolean checksumMatches,
            List<AuditEvent> events,
            AuditIntegrityService.Integrity integrity,
            List<AuditIntegrityService.Problem> integrityProblems) {}

    private final ConsentQueryService queries;
    private final ConsentFormService forms;
    private final AuditService auditService;
    private final AuditIntegrityService integrityService;
    private final PdnAccessLogService pdnAccessLog;

    public ConsentEvidenceService(
            ConsentQueryService queries,
            ConsentFormService forms,
            AuditService auditService,
            AuditIntegrityService integrityService,
            PdnAccessLogService pdnAccessLog) {
        this.queries = queries;
        this.forms = forms;
        this.auditService = auditService;
        this.integrityService = integrityService;
        this.pdnAccessLog = pdnAccessLog;
    }

    @Transactional(readOnly = true)
    public Dossier of(UUID consentId) {
        Consent consent = queries.get(consentId).consent();
        pdnAccessLog.recordSingle("/api/v1/consents/{id}/evidence", consent.getSubjectId());

        ConsentForm form = consent.getFormId() == null ? null : forms.get(consent.getFormId());
        String text = form == null ? null : forms.canonicalText(form);
        String recalculated = form == null ? null : forms.checksumOf(form);
        boolean matches =
                consent.getFormChecksum() != null && consent.getFormChecksum().equals(recalculated);

        var report = integrityService.verifyAggregate(
                ConsentRegistrationService.AGGREGATE_TYPE, consent.getId().toString());

        return new Dossier(
                consent,
                form,
                text,
                consent.getFormChecksum(),
                recalculated,
                matches,
                auditService.historyOf(
                        ConsentRegistrationService.AGGREGATE_TYPE,
                        consent.getId().toString()),
                report.integrity(),
                report.problems());
    }

    /** Доказательства без чувствительных значений — для показа в интерфейсе и в отладке (NFR-3). */
    public Map<String, Object> maskedEvidence(Consent consent, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            Map<String, Object> parsed = mapper.readValue(consent.getEvidence(), Map.class);
            return ru.example.cus.registry.domain.EvidenceValidator.withoutSensitiveValues(parsed);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
