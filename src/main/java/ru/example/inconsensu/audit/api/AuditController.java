package ru.example.inconsensu.audit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.audit.application.AuditQueryService;
import ru.example.inconsensu.audit.application.AuditVerificationService;
import ru.example.inconsensu.audit.domain.AuditEvent;
import ru.example.inconsensu.audit.domain.AuditVerification;
import ru.example.inconsensu.audit.domain.PdnAccessLogEntry;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.AuditEventType;

/** §9: журналы аудита и доступа к ПДн, проверка целостности (FR-10.4, FR-10.5). */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
public class AuditController {

    private final AuditQueryService queries;
    private final AuditVerificationService verifications;
    private final InConsensuProperties properties;
    private final ObjectMapper objectMapper;

    public AuditController(
            AuditQueryService queries,
            AuditVerificationService verifications,
            InConsensuProperties properties,
            ObjectMapper objectMapper) {
        this.queries = queries;
        this.verifications = verifications;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/events")
    public PageResponse<AuditEventResponse> events(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<AuditEvent> page = queries.events(
                new AuditQueryService.EventFilter(
                        aggregateType,
                        aggregateId,
                        eventType,
                        actorId,
                        subjectId,
                        from == null ? null : from.toInstant(),
                        to == null ? null : to.toInstant()),
                pageable);
        return PageResponse.of(page, event -> AuditEventResponse.of(event, properties.timezone(), objectMapper));
    }

    @GetMapping("/access-log")
    public PageResponse<PdnAccessLogResponse> accessLog(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<PdnAccessLogEntry> page = queries.accessLog(
                new AuditQueryService.AccessFilter(
                        userId,
                        subjectId,
                        endpoint,
                        from == null ? null : from.toInstant(),
                        to == null ? null : to.toInstant()),
                pageable);
        return PageResponse.of(page, entry -> PdnAccessLogResponse.of(entry, properties.timezone()));
    }

    /** @param problems список нарушений в формате JSON; пусто, пока проверка не завершилась */
    public record VerificationResponse(
            UUID id,
            String status,
            String statusRu,
            String integrity,
            long aggregatesChecked,
            long eventsChecked,
            long anchorsChecked,
            String problems,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String startedBy) {

        static VerificationResponse of(AuditVerification verification, java.time.ZoneId zone) {
            return new VerificationResponse(
                    verification.getId(),
                    verification.getStatus().name(),
                    verification.getStatus().nameRu(),
                    verification.getIntegrity(),
                    verification.getAggregatesChecked(),
                    verification.getEventsChecked(),
                    verification.getAnchorsChecked(),
                    verification.getProblems(),
                    ru.example.inconsensu.common.api.ApiTime.at(verification.getStartedAt(), zone),
                    ru.example.inconsensu.common.api.ApiTime.at(verification.getFinishedAt(), zone),
                    verification.getStartedBy());
        }
    }

    /**
     * FR-10.4: запуск асинхронной проверки целостности всех хеш-цепочек и якорей.
     *
     * <p>Ответ возвращается сразу с идентификатором запуска: на объёмах NFR-1 полный пересчёт не
     * укладывается в таймаут HTTP. Результат читается по {@code GET /audit/verify/{jobId}} (§9, ADR-0032),
     * история запусков — по {@code GET /audit/verify}.
     */
    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public VerificationResponse verify() {
        return VerificationResponse.of(verifications.start(), properties.timezone());
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public List<VerificationResponse> verifications() {
        return verifications.history().stream()
                .map(verification -> VerificationResponse.of(verification, properties.timezone()))
                .toList();
    }

    @GetMapping("/verify/{jobId}")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public VerificationResponse verification(@PathVariable UUID jobId) {
        return VerificationResponse.of(verifications.get(jobId), properties.timezone());
    }
}
