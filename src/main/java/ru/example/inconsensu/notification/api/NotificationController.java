package ru.example.inconsensu.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.application.NotificationTestService;

/** §9: журнал уведомлений и тестовые отправки. Доступ — ADMIN и DPO (Приложение E). */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAnyRole('ADMIN','DPO')")
public class NotificationController {

    public record TestEmailRequest(@NotBlank @Email @Size(max = 255) String email) {}

    /** @param error пусто при успешной отправке; текст ошибки SMTP — при неудаче */
    public record TestEmailResponse(String email, boolean sent, String error) {}

    private final NotificationService notifications;
    private final NotificationTestService testService;

    public NotificationController(NotificationService notifications, NotificationTestService testService) {
        this.notifications = notifications;
        this.testService = testService;
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(notifications.list(pageable), NotificationResponse::of);
    }

    @PostMapping("/test-email")
    @Operation(summary = "Тестовое письмо (FR-9.5)", description = "Проверяет настройки SMTP и шаблоны")
    public TestEmailResponse testEmail(@Valid @RequestBody TestEmailRequest request) {
        String error = testService.sendTestEmail(request.email());
        return new TestEmailResponse(request.email(), error == null, error);
    }

    @PostMapping("/run")
    @Operation(
            summary = "Внеочередной запуск задачи уведомлений",
            description = "Нужен после сбоя планировщика; дедупликация не даст отправить письма повторно")
    @PreAuthorize("hasRole('ADMIN')")
    public NotificationTestService.RunResult run() {
        return testService.runNow();
    }
}
