package ru.example.cus.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.common.api.PageResponse;
import ru.example.cus.notification.application.WebhookSubscriptionService;

/** §9: подписки внешних систем на события. Управление — ADMIN и INTEGRATION (Приложение E). */
@RestController
@RequestMapping("/api/v1/webhooks")
// §9 и Приложение E: подписками управляет только ADMIN — адрес подписки определяет, куда уходят события.
@PreAuthorize("hasRole('ADMIN')")
public class WebhookController {

    public record SubscriptionRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 1024) String url,
            Set<String> eventTypes,
            Map<String, String> headers,
            Boolean active) {

        WebhookSubscriptionService.SubscriptionForm toForm() {
            return new WebhookSubscriptionService.SubscriptionForm(
                    name,
                    url,
                    eventTypes == null ? Set.of() : eventTypes,
                    headers == null ? Map.of() : headers,
                    active == null || active);
        }
    }

    /** @param secret показывается только здесь: восстановить его позже нельзя, только заменить */
    public record CreatedSubscriptionResponse(WebhookSubscriptionResponse subscription, String secret) {}

    private final WebhookSubscriptionService service;

    public WebhookController(WebhookSubscriptionService service) {
        this.service = service;
    }

    @GetMapping
    public List<WebhookSubscriptionResponse> list() {
        return service.list().stream().map(WebhookSubscriptionResponse::of).toList();
    }

    @GetMapping("/{id}")
    public WebhookSubscriptionResponse get(@PathVariable UUID id) {
        return WebhookSubscriptionResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать подписку", description = "Секрет подписи возвращается один раз, в ответе на создание")
    public CreatedSubscriptionResponse create(@Valid @RequestBody SubscriptionRequest request) {
        WebhookSubscriptionService.Created created = service.create(request.toForm());
        return new CreatedSubscriptionResponse(
                WebhookSubscriptionResponse.of(created.subscription()), created.secret());
    }

    @PutMapping("/{id}")
    public WebhookSubscriptionResponse update(@PathVariable UUID id, @Valid @RequestBody SubscriptionRequest request) {
        return WebhookSubscriptionResponse.of(service.update(id, request.toForm()));
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Заменить секрет подписи", description = "Новый секрет возвращается один раз")
    public CreatedSubscriptionResponse rotateSecret(@PathVariable UUID id) {
        WebhookSubscriptionService.Created created = service.rotateSecret(id);
        return new CreatedSubscriptionResponse(
                WebhookSubscriptionResponse.of(created.subscription()), created.secret());
    }

    @PostMapping("/{id}/deactivate")
    public WebhookSubscriptionResponse deactivate(@PathVariable UUID id) {
        return WebhookSubscriptionResponse.of(service.deactivate(id));
    }

    @PostMapping("/{id}/test")
    @Operation(
            summary = "Тестовая отправка (FR-9.5)",
            description = "Отправляет событие test.ping и возвращает результат попытки")
    public WebhookDeliveryResponse test(@PathVariable UUID id) {
        return WebhookDeliveryResponse.of(service.sendTest(id));
    }

    @GetMapping("/{id}/deliveries")
    public PageResponse<WebhookDeliveryResponse> deliveries(
            @PathVariable UUID id, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(service.deliveriesOf(id, pageable), WebhookDeliveryResponse::of);
    }
}
