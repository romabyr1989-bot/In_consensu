package ru.example.inconsensu.ui.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import ru.example.inconsensu.catalog.application.CatalogCsvWriter;
import ru.example.inconsensu.catalog.application.CatalogExportService;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.ui.application.UiCatalogViewService;
import ru.example.inconsensu.ui.application.UiSorting;

/** UI-6 … UI-10: типы согласий, список форм, конструктор, согласование и просмотр версии. */
@Controller
@PreAuthorize("isAuthenticated()")
public class UiCatalogController {

    private static final int PAGE_SIZE = 50;

    private final UiCatalogViewService view;
    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final CatalogExportService export;
    private final CatalogCsvWriter csv;

    public UiCatalogController(
            UiCatalogViewService view,
            ConsentTypeService types,
            ConsentFormService forms,
            FormWorkflowService workflow,
            CatalogExportService export,
            CatalogCsvWriter csv) {
        this.view = view;
        this.forms = forms;
        this.types = types;
        this.workflow = workflow;
        this.export = export;
        this.csv = csv;
    }

    /**
     * Выгрузка каталога из интерфейса (FR-3.3, UI-12).
     *
     * <p>Отдельная точка, а не ссылка на `/api/v1/catalog/export`: машинная цепочка §12 требует JWT,
     * и браузер с сессионной кукой получил бы там 401.
     */
    @GetMapping("/ui/catalog/export")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "types") CatalogExportService.Part part) {
        String filename = "catalog-" + part.name().toLowerCase(Locale.ROOT) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.write(part, export.snapshot()));
    }

    // ---------- UI-6: типы согласий ----------

    @GetMapping("/ui/catalog/types")
    public String types(
            @RequestParam(required = false) ConsentCategory category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String edit,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        model.addAttribute("types", view.types(category, active, sort, UiSorting.descending(direction)));
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedActive", active);
        model.addAttribute("categories", ConsentCategory.values());
        model.addAttribute("channels", CommunicationChannel.values());
        // UI-6: форма одна на создание и правку; код задаётся только при создании.
        model.addAttribute("editing", edit == null || edit.isBlank() ? null : types.getByCode(edit));
        return "ui/catalog/types";
    }

    @PostMapping("/ui/catalog/types")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String saveType(
            @RequestParam String code,
            @RequestParam String nameRu,
            @RequestParam(required = false) String description,
            @RequestParam ConsentCategory category,
            @RequestParam(required = false) Set<CommunicationChannel> channels,
            @RequestParam(defaultValue = "false") boolean requiresThirdParty,
            @RequestParam(required = false) String defaultValidity,
            @RequestParam(required = false) String dependsOnCode,
            @RequestParam(defaultValue = "false") boolean businessSignificant,
            @RequestParam(defaultValue = "false") boolean update,
            @RequestParam(defaultValue = "0") int sortOrder,
            RedirectAttributes redirect) {
        ConsentTypeService.ConsentTypeForm form = new ConsentTypeService.ConsentTypeForm(
                nameRu,
                description,
                category,
                channels == null ? Set.of() : channels,
                requiresThirdParty,
                blankToNull(defaultValidity),
                blankToNull(dependsOnCode),
                businessSignificant,
                // Порядок сортировки приходит из формы: жёсткий ноль при правке обнулял бы его и
                // перемешивал список типов в конструкторе форм.
                sortOrder);
        try {
            if (update) {
                types.update(code, form);
                redirect.addFlashAttribute("flashMessage", "Тип согласия обновлён");
            } else {
                types.create(code, form);
                redirect.addFlashAttribute("flashMessage", "Тип согласия создан");
            }
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            if (update) {
                // Возвращаем на ту же форму правки, иначе введённое пропадёт вместе с сообщением.
                return "redirect:/ui/catalog/types?edit=" + code;
            }
        }
        return "redirect:/ui/catalog/types";
    }

    @PostMapping("/ui/catalog/types/{code}/deactivate")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String deactivateType(@PathVariable String code, RedirectAttributes redirect) {
        types.deactivate(code);
        redirect.addFlashAttribute("flashMessage", "Тип согласия деактивирован");
        return "redirect:/ui/catalog/types";
    }

    // ---------- UI-7: список форм ----------

    @GetMapping("/ui/catalog/forms")
    public String forms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) ConsentSource source,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        Sort order = UiSorting.of(sort, direction, FORM_SORT, Sort.by(Sort.Direction.DESC, "updatedAt"));
        model.addAttribute(
                "forms",
                view.forms(status, source, typeCode, thirdPartyId, text, PageRequest.of(page, pageSize(size), order)));
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("pageSize", pageSize(size));
        model.addAttribute("status", status);
        model.addAttribute("source", source);
        model.addAttribute("typeCode", typeCode);
        model.addAttribute("thirdPartyId", thirdPartyId);
        model.addAttribute("text", text);
        model.addAttribute("size", pageSize(size));
        model.addAttribute("statuses", ru.example.inconsensu.common.domain.FormStatus.values());
        model.addAttribute("sources", ConsentSource.values());
        // Все типы, а не только активные: форма может ссылаться на деактивированный тип (FR-1.1).
        model.addAttribute("allTypes", types.allTypes());
        model.addAttribute("thirdParties", view.thirdParties());
        model.addAttribute("awaiting", view.awaitingDecision());
        return "ui/catalog/forms";
    }

    /** UI-0.8: колонки списка форм, по которым разрешена сортировка. */
    private static final java.util.Map<String, String> FORM_SORT = java.util.Map.of(
            "code", "code",
            "title", "title",
            "version", "versionNumber",
            "status", "status",
            "updatedAt", "updatedAt");

    /** UI-0.8: размер страницы выбирается из 20 / 50 / 100; иное значение приводится к ближайшему разрешённому. */
    private static int pageSize(int requested) {
        return List.of(20, 50, 100).contains(requested) ? requested : PAGE_SIZE;
    }

    @PostMapping("/ui/catalog/forms")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String createForm(@RequestParam String code, @RequestParam String title, RedirectAttributes redirect) {
        try {
            var draft = forms.createDraft(
                    code,
                    new ConsentFormService.FormDraft(
                            title, "", "", "", Set.of(ConsentSource.WEBSITE_APPLICATION), List.of()));
            return "redirect:/ui/catalog/forms/" + draft.getId() + "/edit";
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/ui/catalog/forms";
        }
    }

    // ---------- UI-10: просмотр версии ----------

    @GetMapping("/ui/catalog/forms/{id}")
    public String form(@PathVariable UUID id, Model model) {
        model.addAttribute("form", view.form(id));
        model.addAttribute("items", view.items(id));
        return "ui/catalog/form-view";
    }

    // ---------- UI-8: конструктор ----------

    @GetMapping("/ui/catalog/forms/{id}/edit")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("form", view.form(id));
        model.addAttribute("activeTypes", types.activeTypes());
        model.addAttribute("sources", ConsentSource.values());
        model.addAttribute("pdnCategories", view.pdnCategories());
        model.addAttribute("thirdParties", view.thirdParties());
        return "ui/catalog/form-edit";
    }

    @PostMapping("/ui/catalog/forms/{id}/edit")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String saveDraft(@PathVariable UUID id, HttpServletRequest request, RedirectAttributes redirect) {
        try {
            view.saveDraft(id, request);
            redirect.addFlashAttribute("flashMessage", "Черновик сохранён");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/catalog/forms/" + id + "/edit";
    }

    @GetMapping("/ui/catalog/forms/{id}/preview")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String preview(@PathVariable UUID id, Model model) {
        model.addAttribute("form", view.form(id));
        return "ui/catalog/fragments :: preview";
    }

    @PostMapping("/ui/catalog/forms/{id}/submit")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            workflow.submit(id);
            redirect.addFlashAttribute("flashMessage", "Форма отправлена на согласование");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/catalog/forms/" + id + "/review";
    }

    @PostMapping("/ui/catalog/forms/{id}/delete")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String deleteDraft(@PathVariable UUID id, RedirectAttributes redirect) {
        forms.deleteDraft(id);
        redirect.addFlashAttribute("flashMessage", "Черновик удалён");
        return "redirect:/ui/catalog/forms";
    }

    // ---------- UI-9: согласование ----------

    @GetMapping("/ui/catalog/forms/{id}/review")
    public String review(@PathVariable UUID id, Model model) {
        model.addAttribute("form", view.form(id));
        model.addAttribute("items", view.items(id));
        model.addAttribute("history", workflow.historyOf(id));
        // UI-9: по каждой обязательной роли — решение с именем согласующего и датой.
        model.addAttribute("approvals", view.approvals(id));
        // Этап 8: юрист видит, что изменилось по сравнению с предыдущей опубликованной версией (FR-3.2).
        view.previousVersion(id).ifPresent(previous -> {
            model.addAttribute("previousVersion", previous);
            model.addAttribute("diff", view.diff(previous.getId(), id));
        });
        return "ui/catalog/form-review";
    }

    @PostMapping("/ui/catalog/forms/{id}/approve")
    @PreAuthorize("hasAnyRole('LAWYER','DPO')")
    public String approve(
            @PathVariable UUID id, @RequestParam(required = false) String comment, RedirectAttributes redirect) {
        try {
            workflow.approve(id, comment);
            redirect.addFlashAttribute("flashMessage", "Форма одобрена");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/catalog/forms/" + id + "/review";
    }

    @PostMapping("/ui/catalog/forms/{id}/reject")
    @PreAuthorize("hasAnyRole('LAWYER','DPO')")
    public String reject(@PathVariable UUID id, @RequestParam String comment, RedirectAttributes redirect) {
        try {
            workflow.reject(id, comment);
            redirect.addFlashAttribute("flashMessage", "Форма возвращена на доработку");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/catalog/forms/" + id + "/review";
    }

    @PostMapping("/ui/catalog/forms/{id}/publish")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public String publish(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            workflow.publish(id);
            redirect.addFlashAttribute("flashMessage", "Версия опубликована");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/catalog/forms/" + id;
    }

    @PostMapping("/ui/catalog/forms/{id}/archive")
    @PreAuthorize("hasAnyRole('DPO','ADMIN')")
    public String archive(@PathVariable UUID id, RedirectAttributes redirect) {
        workflow.archive(id);
        redirect.addFlashAttribute("flashMessage", "Форма отправлена в архив");
        return "redirect:/ui/catalog/forms/" + id;
    }

    @PostMapping("/ui/catalog/forms/{id}/new-version")
    @PreAuthorize("hasAnyRole('LAWYER','DPO','ADMIN')")
    public String newVersion(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            var created = forms.createNewVersion(id);
            return "redirect:/ui/catalog/forms/" + created.getId() + "/edit";
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/ui/catalog/forms/" + id;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
