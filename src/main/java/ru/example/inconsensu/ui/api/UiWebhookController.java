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
import ru.example.inconsensu.common.domain.EventTypes;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.notification.application.OutboxQueryService;
import ru.example.inconsensu.notification.application.WebhookSubscriptionService;

/** UI-14: подписки на события, тестовая отправка и журнал доставок. */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UiWebhookController {

    private static final Set<String> EVENT_TYPES = Set.of(
            EventTypes.CONSENT_GRANTED,
            EventTypes.CONSENT_REVOKED,
            EventTypes.CONSENT_SUPERSEDED,
            EventTypes.CONSENT_EXPIRING,
            EventTypes.CONSENT_EXPIRED,
            EventTypes.FORM_PUBLISHED,
            EventTypes.THIRD_PARTY_CONTRACT_EXPIRING,
            EventTypes.IMPORT_FINISHED);

    private final WebhookSubscriptionService subscriptions;
    private final OutboxQueryService outbox;

    public UiWebhookController(WebhookSubscriptionService subscriptions, OutboxQueryService outbox) {
        this.subscriptions = subscriptions;
        this.outbox = outbox;
    }

    @GetMapping("/ui/webhooks")
    public String list(Model model) {
        model.addAttribute("subscriptions", subscriptions.list());
        model.addAttribute("eventTypes", EVENT_TYPES.stream().sorted().toList());
        model.addAttribute("failed", outbox.failed(20));
        return "ui/webhooks/list";
    }

    @GetMapping("/ui/webhooks/{id}/deliveries")
    public String deliveries(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("subscription", subscriptions.get(id));
        model.addAttribute("deliveries", subscriptions.deliveriesOf(id, PageRequest.of(page, 50)));
        return "ui/webhooks/deliveries";
    }

    @PostMapping("/ui/webhooks")
    public String create(
            @RequestParam String name,
            @RequestParam String url,
            @RequestParam(required = false) Set<String> eventTypes,
            RedirectAttributes redirect) {
        try {
            var created = subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                    name, url, eventTypes == null ? Set.of() : eventTypes, Map.of(), true));
            // UI-14: секрет показывается один раз — дальше его можно только заменить.
            redirect.addFlashAttribute("flashMessage", "Подписка создана. Секрет подписи: " + created.secret());
        } catch (ApiException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/ui/webhooks";
    }

    @PostMapping("/ui/webhooks/{id}/test")
    public String test(@PathVariable UUID id, RedirectAttributes redirect) {
        var delivery = subscriptions.sendTest(id);
        if (delivery.isSuccessful()) {
            redirect.addFlashAttribute(
                    "flashMessage", "Тестовое событие доставлено, код ответа " + delivery.getResponseCode());
        } else {
            redirect.addFlashAttribute("flashError", "Доставка не удалась: " + delivery.getError());
        }
        return "redirect:/ui/webhooks";
    }

    @PostMapping("/ui/webhooks/{id}/rotate-secret")
    public String rotateSecret(@PathVariable UUID id, RedirectAttributes redirect) {
        var created = subscriptions.rotateSecret(id);
        redirect.addFlashAttribute("flashMessage", "Новый секрет подписи: " + created.secret());
        return "redirect:/ui/webhooks";
    }

    @PostMapping("/ui/webhooks/{id}/deactivate")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirect) {
        subscriptions.deactivate(id);
        redirect.addFlashAttribute("flashMessage", "Подписка выключена");
        return "redirect:/ui/webhooks";
    }

    @PostMapping("/ui/webhooks/events/{eventId}/retry")
    public String retryEvent(@PathVariable UUID eventId, RedirectAttributes redirect) {
        outbox.retry(eventId);
        redirect.addFlashAttribute("flashMessage", "Событие возвращено в очередь доставки");
        return "redirect:/ui/webhooks";
    }
}
