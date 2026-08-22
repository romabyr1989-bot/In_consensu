package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.audit.application.AuditQueryService;
import ru.example.inconsensu.audit.application.AuditVerificationService;
import ru.example.inconsensu.audit.domain.AuditVerification;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.notification.application.OutboxQueryService;
import ru.example.inconsensu.notification.application.WebhookSubscriptionService;
import ru.example.inconsensu.ui.application.UiAuditViewService;
import ru.example.inconsensu.ui.application.UiSettingsCatalog;
import ru.example.inconsensu.ui.application.UiWebhookViewService;

/**
 * JSON для webhooks, аудита и администрирования (UI-14 … UI-17).
 *
 * <p>Права у разделов разные, поэтому проверка стоит на каждом методе, а не на классе: аудит открыт
 * аудитору, настройки — ещё и ответственному за ПДн, учётные записи — только администратору.
 */
@RestController
@RequestMapping("/ui/api")
@PreAuthorize("isAuthenticated()")
public class UiAdminApiController {

    private final UiWebhookViewService webhookView;
    private final WebhookSubscriptionService subscriptions;
    private final OutboxQueryService outbox;
    private final UiAuditViewService auditView;
    private final AuditVerificationService verifications;
    private final UserService users;
    private final OperatorSettingsService settings;
    private final ru.example.inconsensu.audit.application.AuditService audit;
    private final ZoneId zone;

    private final ru.example.inconsensu.ui.application.UiFormats formats;

    public UiAdminApiController(
            UiWebhookViewService webhookView,
            WebhookSubscriptionService subscriptions,
            OutboxQueryService outbox,
            UiAuditViewService auditView,
            AuditVerificationService verifications,
            UserService users,
            OperatorSettingsService settings,
            ru.example.inconsensu.audit.application.AuditService audit,
            java.time.Clock clock,
            ru.example.inconsensu.ui.application.UiFormats formats) {
        this.webhookView = webhookView;
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.auditView = auditView;
        this.verifications = verifications;
        this.users = users;
        this.settings = settings;
        this.audit = audit;
        this.zone = clock.getZone();
        this.formats = formats;
    }

    // ---------- UI-14: webhooks ----------

    @GetMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UiWebhookViewService.SubscriptionRow> webhooks() {
        return webhookView.subscriptions();
    }

    /** @param subscriptionId пусто — заводится новая подписка, иначе правится существующая */
    public record SubscriptionRequest(UUID subscriptionId, String name, String url, Set<String> eventTypes) {}

    /**
     * @param secret показывается один раз, при создании: дальше его можно только заменить, поэтому
     *     потребителя настраивают сразу
     */
    public record SubscriptionSaved(List<UiWebhookViewService.SubscriptionRow> rows, String secret) {}

    @PostMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionSaved saveWebhook(@RequestBody SubscriptionRequest request) {
        var form = new WebhookSubscriptionService.SubscriptionForm(
                request.name(),
                request.url(),
                request.eventTypes() == null ? Set.of() : request.eventTypes(),
                Map.of(),
                true);
        if (request.subscriptionId() != null) {
            subscriptions.update(request.subscriptionId(), form);
            return new SubscriptionSaved(webhookView.subscriptions(), null);
        }
        var created = subscriptions.create(form);
        return new SubscriptionSaved(webhookView.subscriptions(), created.secret());
    }

    /** UI-14: подписку выключают, а не удаляют — история доставок остаётся доказательством. */
    @PostMapping("/webhooks/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UiWebhookViewService.SubscriptionRow> deactivateWebhook(@PathVariable UUID id) {
        subscriptions.deactivate(id);
        return webhookView.subscriptions();
    }

    /**
     * UI-14: замена секрета подписи.
     *
     * <p>Новый секрет показывается один раз — как и при создании: хранить его в интерфейсе негде, он
     * нужен только для настройки потребителя.
     */
    @PostMapping("/webhooks/{id}/rotate-secret")
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionSaved rotateSecret(@PathVariable UUID id) {
        var created = subscriptions.rotateSecret(id);
        return new SubscriptionSaved(webhookView.subscriptions(), created.secret());
    }

    /** Типы событий, на которые можно подписаться (§10): список задаёт сервер, а не экран. */
    @GetMapping("/webhooks/event-types")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> eventTypes() {
        return List.of(
                ru.example.inconsensu.common.domain.EventTypes.CONSENT_GRANTED,
                ru.example.inconsensu.common.domain.EventTypes.CONSENT_REVOKED,
                ru.example.inconsensu.common.domain.EventTypes.CONSENT_SUPERSEDED,
                ru.example.inconsensu.common.domain.EventTypes.CONSENT_EXPIRING,
                ru.example.inconsensu.common.domain.EventTypes.CONSENT_EXPIRED,
                ru.example.inconsensu.common.domain.EventTypes.FORM_PUBLISHED,
                ru.example.inconsensu.common.domain.EventTypes.THIRD_PARTY_CONTRACT_EXPIRING,
                ru.example.inconsensu.common.domain.EventTypes.IMPORT_FINISHED);
    }

    @GetMapping("/webhooks/{id}/deliveries")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deliveries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = webhookView.deliveries(id, PageRequest.of(Math.max(page, 0), size));
        return Map.of("rows", found.getContent(), "total", found.getTotalElements());
    }

    @PostMapping("/webhooks/{id}/deliveries/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> retryDelivery(@PathVariable UUID id, @PathVariable UUID eventId) {
        outbox.retry(eventId);
        return Map.of("message", "Событие возвращено в очередь доставки");
    }

    @PostMapping("/webhooks/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> testWebhook(@PathVariable UUID id) {
        var delivery = subscriptions.sendTest(id);
        return delivery.isSuccessful()
                ? Map.of("message", "Тестовое событие доставлено, код ответа " + delivery.getResponseCode())
                : Map.of("error", "Доставка не удалась: " + delivery.getError());
    }

    // ---------- UI-15: аудит ----------

    @GetMapping("/audit/events")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public Map<String, Object> auditEvents(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new AuditQueryService.EventFilter(
                blankToNull(aggregateType), null, eventType, blankToNull(actorId), subjectId, start(from), end(to));
        var found =
                auditView.events(filter, PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "id")));
        return Map.of("rows", found.getContent(), "total", found.getTotalElements());
    }

    @GetMapping("/audit/access-log")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public Map<String, Object> accessLog(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new AuditQueryService.AccessFilter(null, subjectId, blankToNull(endpoint), start(from), end(to));
        var found = auditView.accessLog(
                filter, PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "id")));
        return Map.of("rows", found.getContent(), "total", found.getTotalElements());
    }

    /**
     * Справочники экрана аудита: типы объектов и типы событий.
     *
     * <p>Типы объектов перечисляет сервер: это имена агрегатов, которыми он же подписывает события, и
     * держать их копию во фронте значило бы догадываться о внутренних именах.
     */
    @GetMapping("/audit/options")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public Map<String, Object> auditOptions() {
        return Map.of(
                "aggregateTypes",
                        auditView.aggregateTypes().stream()
                                .map(type -> Map.of("code", type, "nameRu", type))
                                .toList(),
                "eventTypes",
                        java.util.Arrays.stream(AuditEventType.values())
                                .map(type -> Map.of("code", type.name(), "nameRu", type.nameRu()))
                                .toList());
    }

    /** Прогон проверки цепочки: он идёт в фоне, поэтому список показывает и выполняющиеся (FR-10.3). */
    public record VerificationRow(
            UUID id,
            String status,
            String startedBy,
            String startedAt,
            String finishedAt,
            String integrity,
            long aggregatesChecked,
            long eventsChecked,
            long anchorsChecked,
            String problems,
            String error) {}

    @GetMapping("/audit/integrity")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public List<VerificationRow> integrity() {
        return verifications.history().stream().map(this::verificationRow).toList();
    }

    @PostMapping("/audit/integrity")
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    public List<VerificationRow> startVerification() {
        verifications.start();
        return integrity();
    }

    private VerificationRow verificationRow(AuditVerification verification) {
        return new VerificationRow(
                verification.getId(),
                verification.getStatus().name(),
                verification.getStartedBy(),
                verification.getStartedAt() == null ? "" : formats.dateTime(verification.getStartedAt()),
                verification.getFinishedAt() == null ? "" : formats.dateTime(verification.getFinishedAt()),
                verification.getIntegrity(),
                verification.getAggregatesChecked(),
                verification.getEventsChecked(),
                verification.getAnchorsChecked(),
                verification.getProblems(),
                verification.getError());
    }

    // ---------- UI-16, UI-17: администрирование ----------

    public record UserRow(
            UUID id,
            String login,
            String fullName,
            String email,
            List<String> roles,
            boolean active,
            String lastLoginAt) {}

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> users(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        var found = users.list(PageRequest.of(Math.max(page, 0), size, Sort.by("login")));
        return Map.of(
                "rows", found.getContent().stream().map(this::userRow).toList(),
                "total", found.getTotalElements(),
                "roles",
                        java.util.Arrays.stream(RoleCode.values())
                                .map(role -> Map.of("code", role.name(), "nameRu", role.nameRu()))
                                .toList());
    }

    private UserRow userRow(AppUser user) {
        return new UserRow(
                user.getId(),
                user.getLogin(),
                user.getFullName(),
                user.getEmail(),
                List.copyOf(user.getRoleCodes()),
                user.isActive(),
                user.getLastLoginAt() == null ? "" : formats.dateTime(user.getLastLoginAt()));
    }

    /** @param password задаётся только при заведении; смена пароля — отдельное действие */
    public record UserRequest(
            UUID id, String login, String password, String fullName, String email, Set<String> roles, boolean active) {}

    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public UserRow saveUser(@RequestBody UserRequest request) {
        if (request.id() == null) {
            return userRow(users.create(
                    request.login(),
                    request.password(),
                    request.fullName(),
                    blankToNull(request.email()),
                    request.roles()));
        }
        return userRow(users.update(
                request.id(), request.fullName(), blankToNull(request.email()), request.roles(), request.active()));
    }

    @PostMapping("/admin/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> resetPassword(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        users.resetPassword(id, body.getOrDefault("password", ""));
        return Map.of("message", "Пароль сброшен");
    }

    /**
     * UI-16: настройки группами и с историей изменений.
     *
     * <p>История — часть экрана, а не журнала аудита: оператор должен видеть, кто и когда менял реквизиты,
     * не уходя в другой раздел.
     */
    public record SettingsView(List<UiSettingsCatalog.SettingGroup> groups, List<SettingChange> history) {}

    /** @param at момент изменения; @param actor кто менял */
    public record SettingChange(String at, String actor, String description) {}

    @GetMapping("/admin/settings")
    @PreAuthorize("hasAnyRole('ADMIN','DPO')")
    public SettingsView settings() {
        var history = audit
                .historyOf(
                        ru.example.inconsensu.iam.application.OperatorSettingsService.AGGREGATE_TYPE,
                        ru.example.inconsensu.iam.application.OperatorSettingsService.AGGREGATE_ID)
                .stream()
                .map(event -> new SettingChange(
                        formats.dateTime(event.getOccurredAt()),
                        event.getActorId() == null ? "" : event.getActorId(),
                        event.getEventType().nameRu()))
                .toList();
        return new SettingsView(UiSettingsCatalog.groups(settings.all()), history);
    }

    @PostMapping("/admin/settings")
    @PreAuthorize("hasAnyRole('ADMIN','DPO')")
    public SettingsView updateSettings(@RequestBody Map<String, String> changes) {
        settings.update(changes);
        return settings();
    }

    private java.time.Instant start(String value) {
        return value == null || value.isBlank()
                ? null
                : LocalDate.parse(value).atStartOfDay(zone).toInstant();
    }

    /** Верхняя граница — начало следующего дня: иначе «по 20.08» отсекало бы весь этот день. */
    private java.time.Instant end(String value) {
        return value == null || value.isBlank()
                ? null
                : LocalDate.parse(value).plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
