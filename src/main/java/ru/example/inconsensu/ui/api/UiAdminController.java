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
import ru.example.inconsensu.ui.application.UiSettingsCatalog;
import ru.example.inconsensu.ui.application.UiSorting;

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

    /** UI-0.8: колонки списка пользователей, по которым разрешена сортировка. */
    private static final java.util.Map<String, String> USER_SORT =
            java.util.Map.of("login", "login", "fullName", "fullName", "email", "email", "active", "active");

    @GetMapping("/ui/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        int pageSize = UiSorting.pageSize(size);
        var order = UiSorting.of(sort, direction, USER_SORT, org.springframework.data.domain.Sort.by("login"));
        model.addAttribute("users", users.list(PageRequest.of(Math.max(page, 0), pageSize, order)));
        model.addAttribute("roles", RoleCode.values());
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("pageSize", pageSize);
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
            ru.example.inconsensu.ui.application.UiFormErrors.report(redirect, e);
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
            ru.example.inconsensu.ui.application.UiFormErrors.report(redirect, e);
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
        // UI-16: настройки показываются группами и с русскими подписями, а не списком технических ключей.
        model.addAttribute("settingGroups", UiSettingsCatalog.groups(settings.all()));
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
            ru.example.inconsensu.ui.application.UiFormErrors.report(redirect, e);
        }
        return "redirect:/ui/admin/settings";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
