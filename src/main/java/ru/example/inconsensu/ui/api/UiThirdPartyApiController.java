package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.PartnerExport;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;
import ru.example.inconsensu.ui.application.UiThirdPartyViewService;

/**
 * JSON карточки третьего лица: реквизиты, договор и выгрузки (UI-11).
 *
 * <p>Скачивание идёт здесь, а не через машинную цепочку §12: та требует JWT, и браузер с сессионной
 * кукой получал бы 401 — кнопка «Скачать» не работала бы ни разу.
 */
@RestController
@RequestMapping("/ui/api/third-parties")
@PreAuthorize("isAuthenticated()")
public class UiThirdPartyApiController {

    private final ThirdPartyService thirdParties;
    private final PartnerExportService exports;
    private final PdnCategoryService pdnCategories;
    private final UiThirdPartyViewService view;

    public UiThirdPartyApiController(
            ThirdPartyService thirdParties,
            PartnerExportService exports,
            PdnCategoryService pdnCategories,
            UiThirdPartyViewService view) {
        this.thirdParties = thirdParties;
        this.exports = exports;
        this.pdnCategories = pdnCategories;
        this.view = view;
    }

    /** UI-11: справочник третьих лиц со счётчиками согласий и состоянием договора. */
    @GetMapping
    public List<UiThirdPartyViewService.PartyRow> list(
            @RequestParam(required = false) String contract,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return view.rows("EXPIRING".equals(contract), sort, "desc".equalsIgnoreCase(direction));
    }

    /**
     * Карточка партнёра.
     *
     * @param contractExpired договор кончился: передавать данные этому партнёру больше нельзя (FR-7.1)
     * @param allowedCategoriesRu разрешённые категории ПДн по-русски, а не кодами справочника (UI-0.4)
     */
    public record PartyCard(
            UUID id,
            String name,
            String shortName,
            String inn,
            String ogrn,
            String address,
            String role,
            String roleRu,
            String contractNumber,
            String contractDate,
            String contractValidUntil,
            boolean contractExpired,
            List<String> allowedPdnCategories,
            String allowedCategoriesRu,
            String contactEmail,
            boolean active,
            List<UiThirdPartyViewService.ExportRow> exports,
            long exportRecords,
            List<String> exportCategories,
            boolean exportAllowed) {}

    @GetMapping("/{id}")
    public PartyCard card(@PathVariable UUID id) {
        ThirdParty party = thirdParties.get(id);
        PartnerExportService.ExportPreview preview = exports.preview(id);
        return new PartyCard(
                party.getId(),
                party.getName(),
                party.getShortName(),
                party.getInn(),
                party.getOgrn(),
                party.getAddress(),
                party.getRole().name(),
                party.getRole().nameRu(),
                party.getContractNumber(),
                party.getContractDate() == null ? "" : party.getContractDate().toString(),
                party.getContractValidUntil() == null
                        ? ""
                        : party.getContractValidUntil().toString(),
                party.isContractExpired(thirdParties.today()),
                List.copyOf(party.getAllowedPdnCategories()),
                view.categoryNames(party.getAllowedPdnCategories()),
                party.getContactEmail(),
                party.isActive(),
                view.exports(id),
                preview.recordsCount(),
                preview.categories(),
                preview.allowedToExport());
    }

    /** @param inn задаётся только при заведении: по нему партнёр опознаётся и правится (FR-7.2) */
    public record PartyRequest(
            UUID id,
            String inn,
            String name,
            String shortName,
            String ogrn,
            String address,
            ThirdPartyRole role,
            String contractNumber,
            String contractDate,
            String contractValidUntil,
            Set<String> allowedPdnCategories,
            String contactEmail) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public PartyCard save(@RequestBody PartyRequest request) {
        var form = new ThirdPartyService.ThirdPartyForm(
                request.name(),
                request.shortName(),
                request.ogrn(),
                request.address(),
                request.role(),
                request.contractNumber(),
                date(request.contractDate()),
                date(request.contractValidUntil()),
                request.allowedPdnCategories() == null ? Set.of() : request.allowedPdnCategories(),
                request.contactEmail());
        ThirdParty saved = request.id() == null
                ? thirdParties.create(request.inn(), form)
                : thirdParties.update(request.id(), form);
        return card(saved.getId());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public PartyCard deactivate(@PathVariable UUID id) {
        thirdParties.deactivate(id);
        return card(id);
    }

    @GetMapping("/{id}/exports")
    public List<UiThirdPartyViewService.ExportRow> exports(@PathVariable UUID id) {
        return view.exports(id);
    }

    /** UI-11: выгрузка формируется по запросу и живёт ограниченное время (FR-7.4). */
    @PostMapping("/{id}/exports")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public Map<String, Object> createExport(@PathVariable UUID id, @RequestParam(defaultValue = "csv") String format) {
        var created = exports.create(id, format);
        return Map.of(
                "message", "Выгрузка сформирована: записей " + created.getRecordsCount(), "exports", view.exports(id));
    }

    @GetMapping("/exports/{exportId}/download")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public ResponseEntity<String> download(@PathVariable UUID exportId) {
        PartnerExport export = exports.download(exportId);
        boolean json = "json".equals(export.getFormat());
        return ResponseEntity.ok()
                .contentType(
                        json
                                ? MediaType.APPLICATION_JSON
                                : new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export-" + export.getId() + (json ? ".json\"" : ".csv\""))
                .body(export.getContent());
    }

    /** Справочники карточки: роли партнёра и категории ПДн, которые ему можно передавать. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "roles",
                        java.util.Arrays.stream(ThirdPartyRole.values())
                                .map(role -> Map.of("code", role.name(), "nameRu", role.nameRu()))
                                .toList(),
                "pdnCategories",
                        pdnCategories.activeCategories().stream()
                                .map(category -> Map.of("code", category.getCode(), "nameRu", category.getNameRu()))
                                .toList());
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
