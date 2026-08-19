package ru.example.cus.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import ru.example.cus.notification.application.NotificationRuleService;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationTrigger;

/** §9: правила уведомлений. Управление — ADMIN и DPO (Приложение E). */
@RestController
@RequestMapping("/api/v1/notification-rules")
@PreAuthorize("hasAnyRole('ADMIN','DPO')")
public class NotificationRuleController {

    public record RuleRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull NotificationTrigger triggerType,
            List<Integer> daysBefore,
            UUID consentTypeId,
            UUID thirdPartyId,
            Set<@Email String> recipientEmails,
            Set<String> recipientRoles,
            @NotNull Set<NotificationChannel> channels,
            Boolean active) {

        NotificationRuleService.RuleForm toForm() {
            return new NotificationRuleService.RuleForm(
                    name,
                    triggerType,
                    daysBefore == null ? List.of() : daysBefore,
                    consentTypeId,
                    thirdPartyId,
                    recipientEmails == null ? Set.of() : Set.copyOf(recipientEmails),
                    recipientRoles == null ? Set.of() : recipientRoles,
                    channels,
                    active == null || active);
        }
    }

    private final NotificationRuleService rules;

    public NotificationRuleController(NotificationRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<NotificationRuleResponse> list() {
        return rules.list().stream().map(NotificationRuleResponse::of).toList();
    }

    @GetMapping("/{id}")
    public NotificationRuleResponse get(@PathVariable UUID id) {
        return NotificationRuleResponse.of(rules.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать правило уведомления", description = "FR-9.1: пороги в днях до окончания срока")
    public NotificationRuleResponse create(@Valid @RequestBody RuleRequest request) {
        return NotificationRuleResponse.of(rules.create(request.toForm()));
    }

    @PutMapping("/{id}")
    public NotificationRuleResponse update(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return NotificationRuleResponse.of(rules.update(id, request.toForm()));
    }

    @PostMapping("/{id}/deactivate")
    public NotificationRuleResponse deactivate(@PathVariable UUID id) {
        return NotificationRuleResponse.of(rules.deactivate(id));
    }
}
