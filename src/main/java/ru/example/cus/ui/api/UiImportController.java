package ru.example.cus.ui.api;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.integration.application.ConsentImportService;
import ru.example.cus.ui.application.UiImportViewService;

/** UI-12: загрузка файла, пробный запуск и отчёт по строкам. */
@Controller
@PreAuthorize("hasAnyRole('DPO','ADMIN')")
public class UiImportController {

    private final ConsentImportService imports;
    private final UiImportViewService view;

    public UiImportController(ConsentImportService imports, UiImportViewService view) {
        this.imports = imports;
        this.view = view;
    }

    @GetMapping("/ui/import")
    public String list(Model model) {
        model.addAttribute("jobs", imports.list(PageRequest.of(0, 50)).getContent());
        return "ui/import/list";
    }

    @PostMapping("/ui/import")
    public String upload(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "LEGACY_DB") String source,
            RedirectAttributes redirect) {
        try {
            byte[] content = file.getBytes();
            // Задача уходит в фон сразу после коммита; страница задачи показывает прогресс (UI-12).
            var job = imports.start(file.getOriginalFilename(), content, source, dryRun);
            return "redirect:/ui/import/" + job.getId();
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/ui/import";
        } catch (java.io.IOException e) {
            redirect.addFlashAttribute("flashError", "Не удалось прочитать файл");
            return "redirect:/ui/import";
        }
    }

    @GetMapping("/ui/import/{id}")
    public String job(@PathVariable UUID id, Model model) {
        model.addAttribute("job", view.job(id));
        return "ui/import/job";
    }

    /** UI-12: прогресс опрашивается HTMX раз в две секунды, пока задача не завершилась. */
    @GetMapping("/ui/import/{id}/progress")
    public String progress(@PathVariable UUID id, Model model) {
        model.addAttribute("job", view.job(id));
        return "ui/import/fragments :: progress";
    }
}
