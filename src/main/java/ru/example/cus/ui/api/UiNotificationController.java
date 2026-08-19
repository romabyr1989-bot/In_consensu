package ru.example.cus.ui.api;

import java.util.List;
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
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.notification.application.NotificationRuleService;
import ru.example.cus.notification.application.NotificationService;
import ru.example.cus.notification.application.NotificationTestService;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationTrigger;

/** UI-13: правила уведомлений и журнал отправленного. */
@Controller
@PreAuthorize("hasAnyRole('DPO','ADMIN')")
public class UiNotificationController {

    private final NotificationRuleService rules;
    private final NotificationService notifications;
    private final NotificationTestService testService;

    public UiNotificationController(
            NotificationRuleService rules, NotificationService notifications, NotificationTestService testService) {
        this.rules = rules;
        this.notifications = notifications;
        this.testService = testService;
    }

    @GetMapping("/ui/notifications")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("rules", rules.list());
        model.addAttribute("notifications", notifications.list(PageRequest.of(page, 50)));
        model.addAttribute("triggers", NotificationTrigger.values());
        model.addAttribute("channels", NotificationChannel.values());
        model.addAttribute("roles", RoleCode.values());
        return "ui/notifications/list";
    }

    @PostMapping("/ui/notifications/rules")
    public String saveRule(
            @RequestParam String name,
            @RequestParam NotificationTrigger triggerType,
            @RequestParam(required = false) String daysBefore,
            @RequestParam(required = false) Set<String> recipientEmails,
            @RequestParam(required = false) Set<String> recipientRoles,
            @RequestParam Set<NotificationChannel> channels,
            RedirectAttributes redirect) {
        try {
            rules.create(new NotificationRuleService.RuleForm(
                    name,
                    triggerType,
                    thresholds(daysBefore),
                    null,
                    null,
                    recipientEmails == null ? Set.of() : recipientEmails,
                    recipientRoles == null ? Set.of() : recipientRoles,
                    channels,
                    true));
            redirect.addFlashAttribute("flashMessage", "Правило создано");
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/notifications";
    }

    @PostMapping("/ui/notifications/rules/{id}/deactivate")
    public String deactivateRule(@PathVariable UUID id, RedirectAttributes redirect) {
        rules.deactivate(id);
        redirect.addFlashAttribute("flashMessage", "Правило выключено");
        return "redirect:/ui/notifications";
    }

    @PostMapping("/ui/notifications/test-email")
    public String testEmail(@RequestParam String email, RedirectAttributes redirect) {
        String error = testService.sendTestEmail(email);
        if (error == null) {
            redirect.addFlashAttribute("flashMessage", "Тестовое письмо отправлено на " + email);
        } else {
            redirect.addFlashAttribute("flashError", "Письмо не отправлено: " + error);
        }
        return "redirect:/ui/notifications";
    }

    /** UI-13: повторная отправка уведомления со статусом «не отправлено». */
    @PostMapping("/ui/notifications/{id}/retry")
    public String retry(@PathVariable UUID id, RedirectAttributes redirect) {
        notifications.retry(id);
        redirect.addFlashAttribute("flashMessage", "Уведомление возвращено в очередь отправки");
        return "redirect:/ui/notifications";
    }

    /** Пороги вводятся строкой «30, 15, 7»: так их проще править, чем набором полей. */
    private static List<Integer> thresholds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,;\\s]+"))
                .filter(part -> !part.isBlank())
                .map(Integer::parseInt)
                .toList();
    }
}
