package ru.example.inconsensu.ui.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.ui.application.UiCatalogViewService;

/**
 * JSON для экранов каталога: типы согласий, формы, конструктор, согласование и просмотр версии
 * (UI-6 … UI-10).
 *
 * <p>Вынесено из {@link UiApiController}: каталог — отдельный маршрут работы, и держать его в одном
 * классе с реестром значило бы собрать полсотни методов в одном файле.
 *
 * <p>Права проверяются здесь, а не только показом кнопок: приложение прячет недоступное, но запрещает
 * операцию сервер.
 */
@RestController
@RequestMapping("/ui/api/catalog")
@PreAuthorize("isAuthenticated()")
public class UiCatalogApiController {

    private final UiCatalogViewService view;
    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final ru.example.inconsensu.catalog.application.CatalogExportService catalogExport;
    private final ru.example.inconsensu.catalog.application.CatalogCsvWriter csv;

    public UiCatalogApiController(
            UiCatalogViewService view,
            ConsentTypeService types,
            ConsentFormService forms,
            FormWorkflowService workflow,
            ru.example.inconsensu.catalog.application.CatalogExportService catalogExport,
            ru.example.inconsensu.catalog.application.CatalogCsvWriter csv) {
        this.view = view;
        this.types = types;
        this.forms = forms;
        this.workflow = workflow;
        this.catalogExport = catalogExport;
        this.csv = csv;
    }

    // ---------- UI-6: типы согласий ----------

    /** @param channels коды каналов; пустой список означает, что тип не управляет каналами */
    public record TypeRow(
            String code,
            String nameRu,
            String description,
            String category,
            String categoryRu,
            List<String> channels,
            boolean requiresThirdParty,
            String defaultValidity,
            String dependsOnCode,
            boolean businessSignificant,
            boolean active,
            int sortOrder,
            long consentsActive,
            long consentsExpiring,
            long consentsRevoked) {}

    @GetMapping("/types")
    public List<TypeRow> types(
            @RequestParam(required = false) ConsentCategory category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean businessSignificant,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return view
                .types(category, active, businessSignificant, text, sort, "desc".equalsIgnoreCase(direction))
                .stream()
                .map(UiCatalogApiController::typeRow)
                .toList();
    }

    private static TypeRow typeRow(UiCatalogViewService.TypeRow row) {
        var type = row.type();
        return new TypeRow(
                type.getCode(),
                type.getNameRu(),
                type.getDescription(),
                type.getCategory().name(),
                type.getCategory().nameRu(),
                type.getChannels().stream().map(CommunicationChannel::name).toList(),
                type.isRequiresThirdParty(),
                type.getDefaultValidity(),
                type.getDependsOn() == null ? null : type.getDependsOn().getCode(),
                type.isBusinessSignificant(),
                type.isActive(),
                type.getSortOrder(),
                row.active(),
                row.expiringSoon(),
                row.revoked());
    }

    /** Что приходит из формы типа: код нужен только при создании, дальше он неизменен (FR-1.1). */
    public record TypeRequest(
            String code,
            String nameRu,
            String description,
            ConsentCategory category,
            Set<CommunicationChannel> channels,
            boolean requiresThirdParty,
            String defaultValidity,
            String dependsOnCode,
            boolean businessSignificant,
            int sortOrder,
            boolean update) {}

    @PostMapping("/types")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public TypeRow saveType(@RequestBody TypeRequest request) {
        var form = new ConsentTypeService.ConsentTypeForm(
                request.nameRu(),
                request.description(),
                request.category(),
                request.channels() == null ? Set.of() : request.channels(),
                request.requiresThirdParty(),
                blankToNull(request.defaultValidity()),
                blankToNull(request.dependsOnCode()),
                request.businessSignificant(),
                request.sortOrder());
        var saved = request.update() ? types.update(request.code(), form) : types.create(request.code(), form);
        return typeRow(new UiCatalogViewService.TypeRow(saved, 0, 0, 0));
    }

    @PostMapping("/types/{code}/deactivate")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public Map<String, String> deactivateType(@PathVariable String code) {
        types.deactivate(code);
        return Map.of("message", "Тип согласия деактивирован");
    }

    // ---------- UI-7: список форм ----------

    /** @param editable черновик, который ещё можно править: по нему открывается конструктор */
    public record FormRow(
            UUID id,
            String code,
            String title,
            int version,
            String status,
            String statusRu,
            String updatedAt,
            boolean editable) {}

    /** @param total сколько форм подошло под фильтр: по нему приложение показывает постраничность */
    public record FormPage(List<FormRow> rows, long total, List<FormRow> awaitingDecision) {}

    @GetMapping("/forms")
    public FormPage forms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) ConsentSource source,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = view.forms(
                status,
                source,
                typeCode,
                thirdPartyId,
                text,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new FormPage(
                found.getContent().stream().map(UiCatalogApiController::formRow).toList(),
                found.getTotalElements(),
                view.awaitingDecision().stream()
                        .map(UiCatalogApiController::formRow)
                        .toList());
    }

    private static FormRow formRow(ConsentForm form) {
        return new FormRow(
                form.getId(),
                form.getCode(),
                form.getTitle(),
                form.getVersionNumber(),
                form.getStatus().name(),
                form.getStatus().nameRu(),
                form.getUpdatedAt() == null ? "" : form.getUpdatedAt().toString(),
                form.isEditable());
    }

    /** Заведение черновика: код и название, остальное дозаполняется в конструкторе (UI-7). */
    public record NewFormRequest(String code, String title) {}

    @PostMapping("/forms")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormRow createForm(@RequestBody NewFormRequest request) {
        var draft = forms.createDraft(
                request.code(),
                new ConsentFormService.FormDraft(
                        request.title(), "", "", "", Set.of(ConsentSource.WEBSITE_APPLICATION), List.of()));
        return formRow(draft);
    }

    // ---------- UI-8, UI-9, UI-10: одна форма ----------

    /** Пункт формы в том виде, в каком его правит конструктор: коды, а не русские подписи. */
    public record ItemDraft(
            String typeCode,
            String text,
            List<String> purposes,
            List<String> categories,
            UUID thirdPartyId,
            String validity,
            boolean mandatory) {}

    /** Пункт формы для чтения (UI-9, UI-10): все значения уже по-русски. */
    public record ItemRow(
            String typeRu,
            String text,
            String purposes,
            String pdnCategoriesRu,
            String thirdPartyRu,
            String validityRu,
            boolean mandatory) {}

    /**
     * Форма целиком: одного запроса хватает и конструктору, и согласованию, и просмотру версии.
     *
     * @param checklist реквизиты ч. 4 ст. 9 152-ФЗ и отметка, собран ли каждый (UI-8)
     * @param issuedConsents сколько согласий выдано по этой версии — счётчик UI-10
     */
    public record FormDetails(
            UUID id,
            String code,
            String title,
            int version,
            String status,
            String statusRu,
            String body,
            String processingActions,
            String revocationProcedure,
            List<String> sourceChannels,
            List<ItemDraft> draftItems,
            List<ItemRow> items,
            String previewHtml,
            String checksum,
            boolean valid,
            List<FormValidationResult.Finding> violations,
            List<FormValidationResult.Finding> warnings,
            List<FormValidationResult.Requisite> checklist,
            List<FormRow> versions,
            long issuedConsents,
            boolean editable,
            List<UiCatalogViewService.ApprovalRow> approvals,
            List<FormWorkflowService.HistoryEntry> history) {}

    @GetMapping("/forms/{id}")
    public FormDetails form(@PathVariable UUID id) {
        UiCatalogViewService.FormView found = view.form(id);
        ConsentForm form = found.form();
        return new FormDetails(
                form.getId(),
                form.getCode(),
                form.getTitle(),
                form.getVersionNumber(),
                form.getStatus().name(),
                form.getStatus().nameRu(),
                form.getBody(),
                form.getProcessingActions(),
                form.getRevocationProcedure(),
                form.getSourceChannels().stream().map(ConsentSource::name).toList(),
                form.getItems().stream()
                        .map(item -> new ItemDraft(
                                item.getConsentType().getCode(),
                                item.getText(),
                                item.getPurposes(),
                                item.getPdnCategories(),
                                item.getThirdPartyId(),
                                item.getValidity(),
                                item.isMandatory()))
                        .toList(),
                view.items(id).stream()
                        .map(row -> new ItemRow(
                                row.typeRu(),
                                row.text(),
                                row.purposes(),
                                row.pdnCategoriesRu(),
                                row.thirdPartyRu(),
                                row.validityRu(),
                                row.mandatory()))
                        .toList(),
                found.previewHtml(),
                found.checksum(),
                found.validation().valid(),
                found.validation().violations(),
                found.validation().warnings(),
                found.validation().checklist(),
                found.versions().stream().map(UiCatalogApiController::formRow).toList(),
                found.issuedConsents(),
                form.isEditable(),
                view.approvals(id),
                workflow.historyOf(id));
    }

    /** Черновик целиком: конструктор всегда отправляет полный состав, частичных правок нет (UI-8). */
    public record DraftRequest(
            String title,
            String body,
            String processingActions,
            String revocationProcedure,
            Set<ConsentSource> sourceChannels,
            List<ItemDraft> items) {}

    @PostMapping("/forms/{id}")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormDetails saveDraft(@PathVariable UUID id, @RequestBody DraftRequest request) {
        forms.editDraft(
                id,
                new ConsentFormService.FormDraft(
                        request.title(),
                        request.body(),
                        request.processingActions(),
                        request.revocationProcedure(),
                        request.sourceChannels() == null ? Set.of() : request.sourceChannels(),
                        request.items() == null
                                ? List.of()
                                : request.items().stream()
                                        .map(item -> new ConsentFormService.ItemForm(
                                                item.typeCode(),
                                                item.text(),
                                                item.purposes() == null ? List.of() : item.purposes(),
                                                item.categories() == null ? List.of() : item.categories(),
                                                item.thirdPartyId(),
                                                blankToNull(item.validity()),
                                                item.mandatory()))
                                        .toList()));
        return form(id);
    }

    @PostMapping("/forms/{id}/delete")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public Map<String, String> deleteDraft(@PathVariable UUID id) {
        forms.deleteDraft(id);
        return Map.of("message", "Черновик удалён");
    }

    /** Переход по маршруту согласования; комментарий обязателен только при отклонении (FR-1.4). */
    public record DecisionRequest(String comment) {}

    @PostMapping("/forms/{id}/submit")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormDetails submit(@PathVariable UUID id) {
        workflow.submit(id);
        return form(id);
    }

    @PostMapping("/forms/{id}/approve")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormDetails approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        workflow.approve(id, request == null ? null : request.comment());
        return form(id);
    }

    @PostMapping("/forms/{id}/reject")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormDetails reject(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        workflow.reject(id, request.comment());
        return form(id);
    }

    /** Публикация — не юристу: он готовит текст, а выпускает версию ответственный за ПДн (Приложение E). */
    @PostMapping("/forms/{id}/publish")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public FormDetails publish(@PathVariable UUID id) {
        workflow.publish(id);
        return form(id);
    }

    @PostMapping("/forms/{id}/archive")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public FormDetails archive(@PathVariable UUID id) {
        workflow.archive(id);
        return form(id);
    }

    /** UI-10: новая версия заводится копией опубликованной, иначе текст пришлось бы набирать заново. */
    @PostMapping("/forms/{id}/new-version")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public FormRow newVersion(@PathVariable UUID id) {
        return formRow(forms.createNewVersion(id));
    }

    /** UI-9: чем эта версия отличается от предыдущей опубликованной (FR-3.2). */
    @GetMapping("/forms/{id}/diff")
    public List<ru.example.inconsensu.catalog.domain.TextDiff.Line> diff(@PathVariable UUID id) {
        return view.previousVersion(id)
                .map(previous -> view.diff(previous.getId(), id))
                .orElse(List.of());
    }

    /**
     * UI-6, FR-3.1: выгрузка каталога файлом.
     *
     * <p>Разделитель — точка с запятой, как в файлах импорта: Excel в русской локали открывает такой файл
     * сразу, а не одной колонкой.
     */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<String> export(
            @RequestParam(defaultValue = "TYPES")
                    ru.example.inconsensu.catalog.application.CatalogExportService.Part part) {
        String filename = "catalog-" + part.name().toLowerCase(java.util.Locale.ROOT) + ".csv";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(
                        new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(csv.write(part, catalogExport.snapshot()));
    }

    /** Справочники конструктора: активные типы, категории ПДн, третьи лица и источники (UI-8). */
    @GetMapping("/builder-options")
    public Map<String, Object> builderOptions() {
        return Map.of(
                "types",
                        types.activeTypes().stream()
                                .map(type -> Map.of(
                                        "code", type.getCode(),
                                        "nameRu", type.getNameRu(),
                                        "requiresThirdParty", type.isRequiresThirdParty()))
                                .toList(),
                "pdnCategories",
                        view.pdnCategories().stream()
                                .map(category -> Map.of("code", category.getCode(), "nameRu", category.getNameRu()))
                                .toList(),
                "thirdParties",
                        view.thirdParties().stream()
                                .map(party -> Map.of("id", party.getId(), "name", party.getName()))
                                .toList(),
                "sources",
                        java.util.Arrays.stream(ConsentSource.values())
                                .map(source -> Map.of("code", source.name(), "nameRu", source.nameRu()))
                                .toList(),
                "statuses",
                        java.util.Arrays.stream(FormStatus.values())
                                .map(status -> Map.of("code", status.name(), "nameRu", status.nameRu()))
                                .toList(),
                "categories",
                        java.util.Arrays.stream(ConsentCategory.values())
                                .map(category -> Map.of("code", category.name(), "nameRu", category.nameRu()))
                                .toList(),
                "channels",
                        java.util.Arrays.stream(CommunicationChannel.values())
                                .map(channel -> Map.of("code", channel.name(), "nameRu", channel.nameRu()))
                                .toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
