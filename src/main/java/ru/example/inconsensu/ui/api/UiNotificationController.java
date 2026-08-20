package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.application.NotificationTestService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationStatus;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.ui.application.UiNotificationViewService;
import ru.example.inconsensu.ui.application.UiSorting;

/** UI-13: правила уведомлений и журнал отправленного. */
@Controller
@PreAuthorize("hasAnyRole('DPO','ADMIN')")
public class UiNotificationController {

    private final NotificationRuleService rules;
    private final NotificationService notifications;
    private final NotificationTestService testService;

    private final UiNotificationViewService view;
    private final ConsentTypeService types;
    private final ThirdPartyService thirdParties;

    public UiNotificationController(
            NotificationRuleService rules,
            NotificationService notifications,
            NotificationTestService testService,
            UiNotificationViewService view,
            ConsentTypeService types,
            ThirdPartyService thirdParties) {
        this.rules = rules;
        this.notifications = notifications;
        this.testService = testService;
        this.view = view;
        this.types = types;
        this.thirdParties = thirdParties;
    }

    @GetMapping("/ui/notifications")
    public String list(
            @RequestParam(defaultValue = "rules") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) UUID ruleId,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        model.addAttribute("tab", "journal".equals(tab) ? "journal" : "rules");
        model.addAttribute("rules", view.rules());
        model.addAttribute(
                "notifications", view.journal(status, ruleId, channel, from, to, page(page, size, sort, direction)));
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("triggers", NotificationTrigger.values());
        model.addAttribute("channels", NotificationChannel.values());
        model.addAttribute("statuses", NotificationStatus.values());
        model.addAttribute("roles", RoleCode.values());
        model.addAttribute("consentTypes", types.activeTypes());
        model.addAttribute("thirdParties", thirdParties.list(Pageable.unpaged()).getContent());
        // Состояние фильтров и страницы держится в URL (UI-0.8): ссылку на отфильтрованный журнал можно
        // отдать коллеге, а обновление страницы не сбрасывает выбранное.
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRuleId", ruleId);
        model.addAttribute("selectedChannel", channel);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
        model.addAttribute("pageSize", size);
        return "ui/notifications/list";
    }

    /** UI-13: просмотр текста письма — что именно ушло получателю. */
    @GetMapping("/ui/notifications/{id}")
    public String message(@PathVariable UUID id, Model model) {
        model.addAttribute("message", view.message(id));
        return "ui/notifications/message";
    }

    /** UI-0.8: колонки журнала, по которым разрешена сортировка. */
    private static final java.util.Map<String, String> JOURNAL_SORT = java.util.Map.of(
            "createdAt", "createdAt", "recipient", "recipient", "subject", "subjectLine", "status", "status");

    /** UI-0.8: размеры страницы фиксированы — 20, 50 или 100. */
    private static PageRequest page(int page, int size, String sort, String direction) {
        var order = UiSorting.of(
                sort,
                direction,
                JOURNAL_SORT,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return PageRequest.of(Math.max(page, 0), UiSorting.pageSize(size), order);
    }

    @PostMapping("/ui/notifications/rules")
    public String saveRule(
            @RequestParam String name,
            @RequestParam NotificationTrigger triggerType,
            @RequestParam(required = false) String daysBefore,
            @RequestParam(required = false) Set<String> recipientEmails,
            @RequestParam(required = false) Set<String> recipientRoles,
            @RequestParam Set<NotificationChannel> channels,
            @RequestParam(required = false) UUID consentTypeId,
            @RequestParam(required = false) UUID thirdPartyId,
            RedirectAttributes redirect) {
        try {
            rules.create(new NotificationRuleService.RuleForm(
                    name,
                    triggerType,
                    thresholds(daysBefore),
                    // Фильтры правила задаются формой: раньше здесь стояли жёсткие null, и правило по
                    // конкретному типу согласия или партнёру завести из интерфейса было нельзя.
                    consentTypeId,
                    thirdPartyId,
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
        return "redirect:/ui/notifications?tab=journal";
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
