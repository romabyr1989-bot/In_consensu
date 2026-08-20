package ru.example.inconsensu.ui.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.PartnerExport;
import ru.example.inconsensu.ui.application.UiCatalogViewService;
import ru.example.inconsensu.ui.application.UiSorting;
import ru.example.inconsensu.ui.application.UiThirdPartyViewService;

/** UI-11: справочник третьих лиц, карточка, договор и выгрузки партнёру. */
@Controller
@PreAuthorize("isAuthenticated()")
public class UiThirdPartyController {

    private final ThirdPartyService thirdParties;
    private final PartnerExportService exports;
    private final UiCatalogViewService catalog;
    private final UiThirdPartyViewService view;

    public UiThirdPartyController(
            ThirdPartyService thirdParties,
            PartnerExportService exports,
            UiCatalogViewService catalog,
            UiThirdPartyViewService view) {
        this.thirdParties = thirdParties;
        this.exports = exports;
        this.catalog = catalog;
        this.view = view;
    }

    @GetMapping("/ui/third-parties")
    public String list(
            @RequestParam(required = false) String contract,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        // UI-2: плитка «Договоров истекает за 30 дней» ведёт в отфильтрованный список, а не в общий.
        model.addAttribute(
                "thirdParties", view.rows("EXPIRING".equals(contract), sort, UiSorting.descending(direction)));
        model.addAttribute("contractFilter", contract);
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("today", thirdParties.today());
        model.addAttribute("roles", ThirdPartyRole.values());
        model.addAttribute("categories", catalog.pdnCategories());
        return "ui/third-parties/list";
    }

    @GetMapping("/ui/third-parties/{id}")
    public String card(@PathVariable UUID id, @RequestParam(defaultValue = "requisites") String tab, Model model) {
        var thirdParty = thirdParties.get(id);
        model.addAttribute("thirdParty", thirdParty);
        model.addAttribute("today", thirdParties.today());
        model.addAttribute("roles", ThirdPartyRole.values());
        model.addAttribute("categories", catalog.pdnCategories());
        model.addAttribute("exports", view.exports(id));
        model.addAttribute("allowedCategoriesRu", view.categoryNames(thirdParty.getAllowedPdnCategories()));
        model.addAttribute("exportPreview", exports.preview(id));
        model.addAttribute("tab", "exports".equals(tab) ? "exports" : "requisites");
        return "ui/third-parties/card";
    }

    /**
     * Скачивание выгрузки из интерфейса (UI-11).
     *
     * <p>Ссылка вела на `/api/v1/exports/{id}/download`, а машинная цепочка §12 требует JWT: браузер с
     * сессионной кукой получал там 401, то есть кнопка «Скачать» не работала ни разу.
     */
    @GetMapping("/ui/exports/{id}/download")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public ResponseEntity<String> download(@PathVariable UUID id) {
        PartnerExport export = exports.download(id);
        boolean json = "json".equals(export.getFormat());
        return ResponseEntity.ok()
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export-" + export.getId() + (json ? ".json\"" : ".csv\""))
                .body(export.getContent());
    }

    @PostMapping("/ui/third-parties")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String create(
            @RequestParam String inn,
            @RequestParam String name,
            @RequestParam(required = false) String shortName,
            @RequestParam(required = false) String ogrn,
            @RequestParam String address,
            @RequestParam ThirdPartyRole role,
            @RequestParam(required = false) String contractNumber,
            @RequestParam(required = false) LocalDate contractDate,
            @RequestParam(required = false) LocalDate contractValidUntil,
            @RequestParam(required = false) Set<String> allowedPdnCategories,
            @RequestParam(required = false) String contactEmail,
            RedirectAttributes redirect) {
        try {
            var created = thirdParties.create(
                    inn,
                    new ThirdPartyService.ThirdPartyForm(
                            name,
                            shortName,
                            ogrn,
                            address,
                            role,
                            contractNumber,
                            contractDate,
                            contractValidUntil,
                            allowedPdnCategories == null ? Set.of() : allowedPdnCategories,
                            contactEmail));
            redirect.addFlashAttribute("flashMessage", "Третье лицо добавлено");
            return "redirect:/ui/third-parties/" + created.getId();
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/ui/third-parties";
        }
    }

    @PostMapping("/ui/third-parties/{id}")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String update(
            @PathVariable UUID id,
            @RequestParam String name,
            @RequestParam(required = false) String shortName,
            @RequestParam(required = false) String ogrn,
            @RequestParam String address,
            @RequestParam ThirdPartyRole role,
            @RequestParam(required = false) String contractNumber,
            @RequestParam(required = false) LocalDate contractDate,
            @RequestParam(required = false) LocalDate contractValidUntil,
            @RequestParam(required = false) Set<String> allowedPdnCategories,
            @RequestParam(required = false) String contactEmail,
            RedirectAttributes redirect) {
        try {
            thirdParties.update(
                    id,
                    new ThirdPartyService.ThirdPartyForm(
                            name,
                            shortName,
                            ogrn,
                            address,
                            role,
                            contractNumber,
                            contractDate,
                            contractValidUntil,
                            allowedPdnCategories == null ? Set.of() : allowedPdnCategories,
                            contactEmail));
            redirect.addFlashAttribute("flashMessage", "Изменения сохранены");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/third-parties/" + id;
    }

    @PostMapping("/ui/third-parties/{id}/deactivate")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirect) {
        thirdParties.deactivate(id);
        redirect.addFlashAttribute("flashMessage", "Третье лицо деактивировано");
        return "redirect:/ui/third-parties/" + id;
    }

    @PostMapping("/ui/third-parties/{id}/exports")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public String createExport(
            @PathVariable UUID id, @RequestParam(defaultValue = "csv") String format, RedirectAttributes redirect) {
        try {
            var created = exports.create(id, format);
            redirect.addFlashAttribute("flashMessage", "Выгрузка сформирована: записей " + created.getRecordsCount());
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/third-parties/" + id + "?tab=exports";
    }
}
