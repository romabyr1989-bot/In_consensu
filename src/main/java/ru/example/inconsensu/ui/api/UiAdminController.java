package ru.example.inconsensu.ui.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.iam.application.UserService;

/** UI-16: пользователи, роли и настройки оператора. */
@Controller
public class UiAdminController {

    private final UserService users;
    private final OperatorSettingsService settings;
    private final AuditService auditService;

    public UiAdminController(UserService users, OperatorSettingsService settings, AuditService auditService) {
        this.users = users;
        this.settings = settings;
        this.auditService = auditService;
    }

    @GetMapping("/ui/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(Model model) {
        model.addAttribute("users", users.list(PageRequest.of(0, 100)).getContent());
        model.addAttribute("roles", RoleCode.values());
        return "ui/admin/users";
    }

    @PostMapping("/ui/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String createUser(
            @RequestParam String login,
            @RequestParam String password,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam Set<String> roles,
            RedirectAttributes redirect) {
        try {
            users.create(login, password, fullName, blankToNull(email), roles);
            redirect.addFlashAttribute("flashMessage", "Пользователь создан");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/admin/users";
    }

    @PostMapping("/ui/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String updateUser(
            @PathVariable UUID id,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam Set<String> roles,
            @RequestParam(defaultValue = "false") boolean active,
            RedirectAttributes redirect) {
        try {
            users.update(id, fullName, blankToNull(email), roles, active);
            redirect.addFlashAttribute("flashMessage", "Учётная запись обновлена");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/admin/users";
    }

    @PostMapping("/ui/admin/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetPassword(@PathVariable UUID id, @RequestParam String password, RedirectAttributes redirect) {
        users.resetPassword(id, password);
        redirect.addFlashAttribute("flashMessage", "Пароль сброшен");
        return "redirect:/ui/admin/users";
    }

    @GetMapping("/ui/admin/settings")
    @PreAuthorize("hasAnyRole('ADMIN','DPO')")
    public String settings(Model model) {
        model.addAttribute("settings", settings.all());
        model.addAttribute(
                "history",
                auditService.historyOf(OperatorSettingsService.AGGREGATE_TYPE, OperatorSettingsService.AGGREGATE_ID));
        return "ui/admin/settings";
    }

    @PostMapping("/ui/admin/settings")
    @PreAuthorize("hasAnyRole('ADMIN','DPO')")
    public String updateSettings(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<String, String> changes = new java.util.LinkedHashMap<>(params);
        changes.remove("_csrf");
        try {
            settings.update(changes);
            redirect.addFlashAttribute("flashMessage", "Настройки сохранены");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/admin/settings";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
