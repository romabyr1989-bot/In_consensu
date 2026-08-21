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
import ru.example.inconsensu.ui.application.UiWebhookViewService;

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

    private final UiWebhookViewService view;

    public UiWebhookController(
            WebhookSubscriptionService subscriptions, OutboxQueryService outbox, UiWebhookViewService view) {
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.view = view;
    }

    @GetMapping("/ui/webhooks")
    public String list(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID edit,
            Model model) {
        model.addAttribute("editing", edit == null ? null : subscriptions.get(edit));
        model.addAttribute(
                "subscriptions",
                ru.example.inconsensu.ui.application.UiSorting.page(
                        view.subscriptions(sort, ru.example.inconsensu.ui.application.UiSorting.descending(direction)),
                        page,
                        size));
        model.addAttribute("pageSize", ru.example.inconsensu.ui.application.UiSorting.pageSize(size));
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("eventTypes", EVENT_TYPES.stream().sorted().toList());
        model.addAttribute("failed", outbox.failed(20));
        return "ui/webhooks/list";
    }

    @GetMapping("/ui/webhooks/{id}/deliveries")
    public String deliveries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        int normalized = size == 50 || size == 100 ? size : 20;
        model.addAttribute("subscription", subscriptions.get(id));
        model.addAttribute("deliveries", view.deliveries(id, PageRequest.of(Math.max(page, 0), normalized)));
        model.addAttribute("pageSize", normalized);
        return "ui/webhooks/deliveries";
    }

    /**
     * UI-14: повторная доставка события прямо со страницы доставок.
     *
     * <p>Повторяется событие outbox, а не строка журнала: журнал доставок — это след попыток, и
     * «повторить попытку» означает вернуть в очередь само событие.
     */
    @PostMapping("/ui/webhooks/{id}/deliveries/{eventId}/retry")
    public String retryDelivery(@PathVariable UUID id, @PathVariable UUID eventId, RedirectAttributes redirect) {
        outbox.retry(eventId);
        redirect.addFlashAttribute("flashMessage", "Событие возвращено в очередь доставки");
        return "redirect:/ui/webhooks/" + id + "/deliveries";
    }

    /**
     * Создание и правка подписки одной формой (UI-0.1, §9 `PUT /webhooks/{id}`).
     *
     * <p>Правки не было: поменять адрес или список событий можно было только выключив подписку и заведя
     * новую — с новым секретом, который пришлось бы прописывать потребителю.
     */
    @PostMapping("/ui/webhooks")
    public String create(
            @RequestParam String name,
            @RequestParam String url,
            @RequestParam(required = false) Set<String> eventTypes,
            @RequestParam(required = false) UUID subscriptionId,
            RedirectAttributes redirect) {
        try {
            var form = new WebhookSubscriptionService.SubscriptionForm(
                    name, url, eventTypes == null ? Set.of() : eventTypes, Map.of(), true);
            if (subscriptionId != null) {
                subscriptions.update(subscriptionId, form);
                redirect.addFlashAttribute("flashMessage", "Подписка изменена");
                return "redirect:/ui/webhooks";
            }
            var created = subscriptions.create(form);
            // UI-14: секрет показывается один раз — дальше его можно только заменить, поэтому он выводится
            // отдельным блоком с кнопкой копирования, а не строкой в тексте сообщения.
            redirect.addFlashAttribute("flashMessage", "Подписка создана");
            redirect.addFlashAttribute("flashSecret", created.secret());
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
        redirect.addFlashAttribute("flashMessage", "Секрет подписи заменён");
        redirect.addFlashAttribute("flashSecret", created.secret());
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
