package ru.example.inconsensu.registry.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.registry.application.ConsentEvidenceService;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;

/** §9: регистрация и чтение согласий (FR-4.1 … FR-4.4). */
@RestController
@RequestMapping("/api/v1/consents")
@PreAuthorize("isAuthenticated()")
public class ConsentController {

    public record ContactPayload(@NotNull ContactType type, String value, boolean primary) {}

    public record SubjectPayload(
            String externalId,
            String lastName,
            String firstName,
            String middleName,
            java.time.LocalDate birthDate,
            List<ContactPayload> contacts) {

        SubjectService.SubjectForm toForm() {
            return new SubjectService.SubjectForm(
                    externalId,
                    lastName,
                    firstName,
                    middleName,
                    birthDate,
                    contacts == null
                            ? List.of()
                            : contacts.stream()
                                    .map(contact -> new SubjectService.ContactForm(
                                            contact.type(), contact.value(), contact.primary()))
                                    .toList());
        }
    }

    public record ItemPayload(@NotNull UUID formItemId, boolean accepted) {}

    public record RegisterConsentRequest(
            String subjectExternalId,
            SubjectPayload subject,
            @NotNull UUID formId,
            @NotNull List<@Valid ItemPayload> items,
            Instant grantedAt,
            @NotNull ConsentSource source,
            String sourceRef,
            @NotNull SignatureType signatureType,
            Map<String, Object> evidence) {}

    public record RegisterConsentResponse(
            List<ConsentResponse> consents, List<UUID> declinedItems, boolean idempotentReplay) {}

    private final ConsentRegistrationService registration;
    private final ConsentQueryService queries;
    private final ConsentResponseAssembler assembler;
    private final ConsentEvidenceService evidenceService;
    private final ru.example.inconsensu.registry.application.RevocationService revocation;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ru.example.inconsensu.common.config.InConsensuProperties properties;

    public ConsentController(
            ConsentRegistrationService registration,
            ConsentQueryService queries,
            ConsentResponseAssembler assembler,
            ConsentEvidenceService evidenceService,
            ru.example.inconsensu.registry.application.RevocationService revocation,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ru.example.inconsensu.common.config.InConsensuProperties properties) {
        this.registration = registration;
        this.queries = queries;
        this.assembler = assembler;
        this.evidenceService = evidenceService;
        this.revocation = revocation;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * FR-4.1: регистрация одного согласия или пакета по одной форме.
     *
     * <p>Повторный вызов с тем же {@code Idempotency-Key} возвращает исходный результат с кодом 200, а не 201:
     * внешняя система, повторившая запрос после таймаута, не должна получить дубликаты.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('INTEGRATION','ADMIN')")
    public ResponseEntity<RegisterConsentResponse> register(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RegisterConsentRequest request) {

        var result = registration.register(
                idempotencyKey,
                new ConsentRegistrationService.RegistrationRequest(
                        request.subjectExternalId(),
                        request.subject() == null ? null : request.subject().toForm(),
                        request.formId(),
                        request.items().stream()
                                .map(item ->
                                        new ConsentRegistrationService.ItemDecision(item.formItemId(), item.accepted()))
                                .toList(),
                        request.grantedAt(),
                        request.source(),
                        request.sourceRef(),
                        request.signatureType(),
                        request.evidence()));

        RegisterConsentResponse body = new RegisterConsentResponse(
                assembler.toResponses(
                        result.created().stream().map(queries::view).toList()),
                result.declinedItems(),
                result.idempotentReplay());

        return ResponseEntity.status(result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(body);
    }

    /** §9: список согласий с фильтрами для отчётов и разбора обращений (FR-3.4, UI-4). */
    @GetMapping
    public ru.example.inconsensu.common.api.PageResponse<ConsentResponse> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID subjectId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String typeCode,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    ru.example.inconsensu.common.domain.ConsentStatus status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID thirdPartyId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    ru.example.inconsensu.common.domain.ConsentSource source,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    java.time.OffsetDateTime validUntilFrom,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    java.time.OffsetDateTime validUntilTo,
            @org.springframework.data.web.PageableDefault(size = 20)
                    org.springframework.data.domain.Pageable pageable) {

        var page = queries.search(
                new ConsentQueryService.ConsentFilter(
                        subjectId,
                        typeCode,
                        status,
                        thirdPartyId,
                        source,
                        validUntilFrom == null ? null : validUntilFrom.toInstant(),
                        validUntilTo == null ? null : validUntilTo.toInstant()),
                pageable);
        return new ru.example.inconsensu.common.api.PageResponse<>(
                assembler.toResponses(
                        page.getContent().stream().map(queries::view).toList()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @GetMapping("/{id}")
    public ConsentResponse get(@PathVariable UUID id) {
        return assembler.toResponse(queries.get(id));
    }

    /** FR-10.3: чем именно оператор докажет наличие согласия, включая проверку целостности журнала. */
    @GetMapping("/{id}/evidence")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN','MANAGER')")
    public ConsentEvidenceResponse evidence(@PathVariable UUID id) {
        var dossier = evidenceService.of(id);
        return ConsentEvidenceResponse.of(
                dossier, evidenceService.maskedEvidence(dossier.consent(), objectMapper), properties.timezone());
    }

    public record RevokeRequest(
            @jakarta.validation.constraints.NotBlank String reason,
            @jakarta.validation.constraints.NotNull ru.example.inconsensu.common.domain.RevocationSource revocationSource,
            @jakarta.validation.constraints.NotBlank String caseNumber,
            java.util.Map<String, Object> evidence) {}

    public record RevocationResponse(
            java.util.UUID consentId,
            java.time.OffsetDateTime revokedAt,
            java.time.OffsetDateTime processingStopDeadline,
            String caseNumber,
            java.util.List<java.util.UUID> cascadedConsentIds) {}

    /**
     * FR-8.2, FR-8.3: отзыв по обращению клиента. Необратим и действует немедленно.
     *
     * <p>Повторный вызов для уже отозванного согласия возвращает 200 и прежние данные: клиент, нажавший
     * кнопку дважды, не должен получить ошибку.
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public RevocationResponse revoke(@PathVariable UUID id, @Valid @RequestBody RevokeRequest request) {
        var result = revocation.revoke(
                id, request.reason(), request.revocationSource(), request.caseNumber(), request.evidence());
        return new RevocationResponse(
                result.revoked().getId(),
                ru.example.inconsensu.common.api.ApiTime.at(result.revokedAt(), properties.timezone()),
                ru.example.inconsensu.common.api.ApiTime.at(result.processingStopDeadline(), properties.timezone()),
                result.caseNumber(),
                result.cascaded().stream()
                        .map(ru.example.inconsensu.registry.domain.Consent::getId)
                        .toList());
    }

    /** UI-5: что погаснет вместе с этим согласием — список показывается до подтверждения отзыва. */
    @GetMapping("/{id}/revocation-preview")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public java.util.List<ConsentResponse> revocationPreview(@PathVariable UUID id) {
        return assembler.toResponses(
                revocation.previewCascade(id).stream().map(queries::view).toList());
    }
}
