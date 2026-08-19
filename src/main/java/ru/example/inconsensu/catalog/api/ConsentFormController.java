package ru.example.inconsensu.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.FormApproval;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.common.api.ApiTime;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.ConsentSource;

/** §9: формы согласий и их согласование (FR-1.2 … FR-1.6, FR-2.1 … FR-2.3). */
@RestController
@RequestMapping("/api/v1/forms")
@PreAuthorize("isAuthenticated()")
public class ConsentFormController {

    public record ItemRequest(
            @NotBlank String consentTypeCode,
            @NotBlank String text,
            List<String> purposes,
            List<String> pdnCategories,
            UUID thirdPartyId,
            @Size(max = 32) String validity,
            boolean mandatory) {}

    public record FormRequest(
            @NotBlank @Size(max = 512) String title,
            @NotBlank String body,
            String processingActions,
            String revocationProcedure,
            Set<ConsentSource> sourceChannels,
            List<@Valid ItemRequest> items) {

        ConsentFormService.FormDraft toDraft() {
            return new ConsentFormService.FormDraft(
                    title,
                    body,
                    processingActions,
                    revocationProcedure,
                    sourceChannels == null ? Set.of() : sourceChannels,
                    items == null
                            ? List.of()
                            : items.stream()
                                    .map(item -> new ConsentFormService.ItemForm(
                                            item.consentTypeCode(),
                                            item.text(),
                                            item.purposes(),
                                            item.pdnCategories(),
                                            item.thirdPartyId(),
                                            item.validity(),
                                            item.mandatory()))
                                    .toList());
        }
    }

    public record CreateFormRequest(
            @NotBlank @Size(max = 64) @Pattern(
                            regexp = "[A-Z0-9_]+",
                            message = "Код формы — заглавные латинские буквы, цифры и подчёркивание")
                    String code,
            @Valid @NotNull FormRequest form) {}

    public record DecisionRequest(String comment) {}

    public record ApprovalResponse(
            String roleRequired,
            String userLogin,
            String decision,
            String decisionRu,
            String comment,
            String decidedAt) {}

    private final ConsentFormService formService;
    private final FormWorkflowService workflow;
    private final InConsensuProperties properties;

    public ConsentFormController(
            ConsentFormService formService, FormWorkflowService workflow, InConsensuProperties properties) {
        this.formService = formService;
        this.workflow = workflow;
        this.properties = properties;
    }

    private ZoneId zone() {
        return properties.timezone();
    }

    /** FR-3.1: список форм с фильтрами по статусу, источнику, типу согласия, третьему лицу и тексту. */
    @GetMapping
    public PageResponse<ConsentFormResponse> list(
            @RequestParam(required = false) ru.example.inconsensu.common.domain.FormStatus status,
            @RequestParam(required = false) ru.example.inconsensu.common.domain.ConsentSource source,
            @RequestParam(required = false) String consentTypeCode,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) String text,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        var filter = new ConsentFormService.FormFilter(status, source, consentTypeCode, thirdPartyId, text);
        return PageResponse.of(formService.list(filter, pageable), form -> ConsentFormResponse.summary(form, zone()));
    }

    @GetMapping("/awaiting-decision")
    public List<ConsentFormResponse> awaitingDecision() {
        return formService.awaitingDecision().stream()
                .map(form -> ConsentFormResponse.summary(form, zone()))
                .toList();
    }

    @GetMapping("/{id}")
    public ConsentFormResponse get(@PathVariable UUID id) {
        return ConsentFormResponse.of(formService.get(id), zone());
    }

    @GetMapping("/{id}/text")
    public Map<String, String> text(@PathVariable UUID id) {
        var form = formService.get(id);
        return Map.of(
                "text", formService.canonicalText(form),
                "checksum",
                        form.getRenderedChecksum() == null ? formService.checksumOf(form) : form.getRenderedChecksum(),
                "version", String.valueOf(form.getVersionNumber()));
    }

    @GetMapping("/{id}/preview")
    public Map<String, String> preview(@PathVariable UUID id) {
        return Map.of("preview", formService.preview(id));
    }

    @PostMapping("/{id}/validate")
    public FormValidationResult validate(@PathVariable UUID id) {
        return formService.validate(id);
    }

    /** @param lines строки сравнения: «+» добавлено, «-» удалено, пробел — без изменений */
    public record DiffResponse(
            java.util.UUID beforeVersionId,
            java.util.UUID afterVersionId,
            boolean changed,
            java.util.List<DiffLine> lines) {}

    public record DiffLine(String marker, String text) {}

    /** FR-3.2, этап 8: текстовый diff двух версий формы. */
    @GetMapping("/{id}/diff")
    public DiffResponse diff(@PathVariable UUID id, @RequestParam UUID against) {
        var lines = formService.diff(against, id);
        return new DiffResponse(
                against,
                id,
                ru.example.inconsensu.catalog.domain.TextDiff.hasChanges(lines),
                lines.stream()
                        .map(line -> new DiffLine(line.kind().marker(), line.text()))
                        .toList());
    }

    /** @param fromStatus статус до перехода; у решений согласующих пуст (FR-2.2) */
    public record HistoryResponse(
            java.time.OffsetDateTime at,
            String eventType,
            String eventTypeRu,
            String fromStatus,
            String toStatus,
            String actor,
            String role,
            String decision,
            String comment) {}

    /** FR-2.2: история формы — переходы статусов и решения согласующих в одной ленте. */
    @GetMapping("/{id}/history")
    public List<HistoryResponse> history(@PathVariable UUID id) {
        return workflow.historyOf(id).stream()
                .map(entry -> new HistoryResponse(
                        ru.example.inconsensu.common.api.ApiTime.at(entry.at(), zone()),
                        entry.eventType(),
                        entry.eventTypeRu(),
                        entry.fromStatus(),
                        entry.toStatus(),
                        entry.actor(),
                        entry.role(),
                        entry.decision(),
                        entry.comment()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public ConsentFormResponse create(@Valid @RequestBody CreateFormRequest request) {
        return ConsentFormResponse.of(
                formService.createDraft(request.code(), request.form().toDraft()), zone());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public ConsentFormResponse update(@PathVariable UUID id, @Valid @RequestBody FormRequest request) {
        return ConsentFormResponse.of(formService.editDraft(id, request.toDraft()), zone());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public void deleteDraft(@PathVariable UUID id) {
        formService.deleteDraft(id);
    }

    @PostMapping("/{id}/new-version")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public ConsentFormResponse newVersion(@PathVariable UUID id) {
        return ConsentFormResponse.of(formService.createNewVersion(id), zone());
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public ConsentFormResponse submit(@PathVariable UUID id) {
        return ConsentFormResponse.of(workflow.submit(id), zone());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('LAWYER','DPO')")
    public ConsentFormResponse approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        return ConsentFormResponse.of(workflow.approve(id, request == null ? null : request.comment()), zone());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('LAWYER','DPO')")
    public ConsentFormResponse reject(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        return ConsentFormResponse.of(workflow.reject(id, request == null ? null : request.comment()), zone());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public ConsentFormResponse publish(@PathVariable UUID id) {
        return ConsentFormResponse.of(workflow.publish(id), zone());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public ConsentFormResponse archive(@PathVariable UUID id) {
        return ConsentFormResponse.of(workflow.archive(id), zone());
    }

    private ApprovalResponse toResponse(FormApproval approval) {
        return new ApprovalResponse(
                approval.getRoleRequired(),
                approval.getUserLogin(),
                approval.getDecision().name(),
                approval.getDecision().nameRu(),
                approval.getComment(),
                String.valueOf(ApiTime.at(approval.getDecidedAt(), zone())));
    }
}
