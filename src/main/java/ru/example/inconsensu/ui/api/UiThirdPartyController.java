package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
import ru.example.inconsensu.ui.application.UiCatalogViewService;

/** UI-11: справочник третьих лиц, карточка, договор и выгрузки партнёру. */
@Controller
@PreAuthorize("isAuthenticated()")
public class UiThirdPartyController {

    private final ThirdPartyService thirdParties;
    private final PartnerExportService exports;
    private final UiCatalogViewService catalog;

    public UiThirdPartyController(
            ThirdPartyService thirdParties, PartnerExportService exports, UiCatalogViewService catalog) {
        this.thirdParties = thirdParties;
        this.exports = exports;
        this.catalog = catalog;
    }

    @GetMapping("/ui/third-parties")
    public String list(Model model) {
        model.addAttribute("thirdParties", thirdParties.list(Pageable.unpaged()).getContent());
        model.addAttribute("today", thirdParties.today());
        model.addAttribute("roles", ThirdPartyRole.values());
        model.addAttribute("categories", catalog.pdnCategories());
        model.addAttribute("consentCounts", catalog.consentCountsByThirdParty());
        return "ui/third-parties/list";
    }

    @GetMapping("/ui/third-parties/{id}")
    public String card(@PathVariable UUID id, Model model) {
        model.addAttribute("thirdParty", thirdParties.get(id));
        model.addAttribute("today", thirdParties.today());
        model.addAttribute("roles", ThirdPartyRole.values());
        model.addAttribute("categories", catalog.pdnCategories());
        model.addAttribute("exports", exports.listFor(id));
        return "ui/third-parties/card";
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
        return "redirect:/ui/third-parties/" + id;
    }
}
