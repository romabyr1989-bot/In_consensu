package ru.example.inconsensu.ui.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.ui.application.UiImportViewService;

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
        model.addAttribute("sources", ConsentSource.values());
        return "ui/import/list";
    }

    /** UI-12: описание формата рядом с загрузкой — сотруднику не нужно искать файл документации. */
    @GetMapping("/ui/import/format")
    public String format(Model model) {
        model.addAttribute("sources", ConsentSource.values());
        return "ui/import/format";
    }

    @PostMapping("/ui/import")
    public String upload(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRunSubmitted,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "CLIENT_BASE_IMPORT") ConsentSource source,
            RedirectAttributes redirect) {
        // Снятый чекбокс не приходит вовсе, поэтому форма присылает скрытый маркер. Без маркера режим
        // пробный: запрос пришёл не из формы, и молча запускать боевой импорт нельзя.
        boolean dry = !dryRunSubmitted || dryRun;
        try {
            byte[] content = file.getBytes();
            // Задача уходит в фон сразу после коммита; страница задачи показывает прогресс (UI-12).
            var job = imports.start(file.getOriginalFilename(), content, source.name(), dry);
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

    /** UI-12: боевой импорт по файлу успешного пробного запуска — кнопкой, без повторной загрузки. */
    @PostMapping("/ui/import/{id}/run")
    public String runForReal(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            var job = imports.runForReal(id);
            redirect.addFlashAttribute("flashMessage", "Боевой импорт запущен");
            return "redirect:/ui/import/" + job.getId();
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/ui/import/" + id;
        }
    }

    /** UI-12: построчный отчёт об ошибках выгружается файлом — его разбирают вне интерфейса. */
    @GetMapping("/ui/import/{id}/report.csv")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-report-" + id + ".csv\"")
                .body(view.reportCsv(id));
    }

    /** UI-12: прогресс опрашивается HTMX раз в две секунды, пока задача не завершилась. */
    @GetMapping("/ui/import/{id}/progress")
    public String progress(@PathVariable UUID id, Model model) {
        model.addAttribute("job", view.job(id));
        return "ui/import/fragments :: progress";
    }
}
