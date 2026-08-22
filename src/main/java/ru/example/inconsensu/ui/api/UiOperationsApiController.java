package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.integration.domain.ImportJob;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.application.NotificationTestService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationStatus;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.ui.application.UiImportViewService;
import ru.example.inconsensu.ui.application.UiNotificationViewService;

/**
 * JSON для повседневных операций: импорт базы и уведомления (UI-12, UI-13).
 *
 * <p>Оба раздела закрыты одинаково — ими занимаются DPO и администратор, поэтому и лежат вместе:
 * дробить на два класса ради двух списков смысла нет.
 */
@RestController
@RequestMapping("/ui/api")
@PreAuthorize("hasAnyRole('DPO','ADMIN')")
public class UiOperationsApiController {

    private final ConsentImportService imports;
    private final UiImportViewService importView;
    private final NotificationRuleService rules;
    private final NotificationService notifications;
    private final NotificationTestService testService;
    private final UiNotificationViewService notificationView;
    private final ru.example.inconsensu.catalog.application.ConsentTypeService types;
    private final ru.example.inconsensu.thirdparty.application.ThirdPartyService thirdParties;

    public UiOperationsApiController(
            ConsentImportService imports,
            UiImportViewService importView,
            NotificationRuleService rules,
            NotificationService notifications,
            NotificationTestService testService,
            UiNotificationViewService notificationView,
            ru.example.inconsensu.catalog.application.ConsentTypeService types,
            ru.example.inconsensu.thirdparty.application.ThirdPartyService thirdParties) {
        this.imports = imports;
        this.importView = importView;
        this.rules = rules;
        this.notifications = notifications;
        this.testService = testService;
        this.notificationView = notificationView;
        this.types = types;
        this.thirdParties = thirdParties;
    }

    // ---------- UI-12: импорт ----------

    /** @param dryRun пробный запуск: строки проверяются, но ничего не пишется (FR-4.5) */
    public record JobRow(
            UUID id,
            String fileName,
            String source,
            boolean dryRun,
            String status,
            String statusRu,
            int total,
            int imported,
            int rejected,
            int percent,
            String startedBy,
            String startedAt,
            String finishedAt) {}

    /** @param report построчные отказы: строка, поле и причина (UI-12) */
    public record JobDetails(JobRow job, List<Map<String, Object>> report) {}

    @GetMapping("/import")
    public Map<String, Object> jobs(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var found = imports.list(PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "startedAt")));
        return Map.of(
                "rows", found.getContent().stream().map(job -> row(job, 0)).toList(),
                "total", found.getTotalElements(),
                "sources",
                        java.util.Arrays.stream(ConsentSource.values())
                                .map(source -> Map.of("code", source.name(), "nameRu", source.nameRu()))
                                .toList());
    }

    @GetMapping("/import/{id}")
    public JobDetails job(@PathVariable UUID id) {
        UiImportViewService.JobView view = importView.job(id);
        return new JobDetails(row(view.job(), view.percent()), view.report());
    }

    private static JobRow row(ImportJob job, int percent) {
        return new JobRow(
                job.getId(),
                job.getFileName(),
                job.getSource(),
                job.isDryRun(),
                job.getStatus().name(),
                job.getStatus().nameRu(),
                job.getTotal(),
                job.getImported(),
                job.getRejected(),
                percent,
                job.getStartedBy(),
                job.getStartedAt() == null ? "" : job.getStartedAt().toString(),
                job.getFinishedAt() == null ? "" : job.getFinishedAt().toString());
    }

    /**
     * Загрузка файла.
     *
     * <p>Пробный запуск — значение по умолчанию: боевой импорт правит базу целиком, и запускать его
     * без явного указания нельзя (FR-4.5).
     */
    @PostMapping("/import")
    public JobRow upload(
            @RequestPart MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "CLIENT_BASE_IMPORT") ConsentSource source)
            throws java.io.IOException {
        var job = imports.start(file.getOriginalFilename(), file.getBytes(), source.name(), dryRun);
        return row(job, 0);
    }

    /** UI-12: боевой импорт по файлу успешного пробного запуска — без повторной загрузки. */
    @PostMapping("/import/{id}/run")
    public JobRow runForReal(@PathVariable UUID id) {
        return row(imports.runForReal(id), 0);
    }

    /** UI-12: построчный отчёт выгружается файлом — его разбирают вне интерфейса. */
    @GetMapping("/import/{id}/report.csv")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-report-" + id + ".csv\"")
                .body(importView.reportCsv(id));
    }

    // ---------- UI-13: уведомления ----------

    @GetMapping("/notifications/rules")
    public List<UiNotificationViewService.RuleRow> rules() {
        return notificationView.rules();
    }

    /**
     * Правило целиком, полями (UI-13).
     *
     * <p>Список правил отдаёт готовые подписи для таблицы, и по ним форму правки не собрать: из строки
     * «Ответственный за ПДн, dpo@example.ru» не восстановить ни роли, ни каналы, ни отбор. Правка,
     * собранная из подписей, молча теряла бы всё, о чём таблица не рассказывает.
     */
    public record RuleDetails(
            UUID id,
            String name,
            String triggerType,
            String daysBefore,
            List<String> recipientEmails,
            List<String> recipientRoles,
            List<String> channels,
            UUID consentTypeId,
            UUID thirdPartyId,
            boolean active) {}

    @GetMapping("/notifications/rules/{id}")
    public RuleDetails rule(@PathVariable UUID id) {
        var rule = rules.get(id);
        return new RuleDetails(
                rule.getId(),
                rule.getName(),
                rule.getTriggerType().name(),
                rule.getDaysBefore().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")),
                List.copyOf(rule.getRecipientEmails()),
                List.copyOf(rule.getRecipientRoles()),
                rule.getChannels().stream().map(NotificationChannel::name).toList(),
                rule.getConsentTypeId(),
                rule.getThirdPartyId(),
                rule.isActive());
    }

    /** @param daysBefore пороги строкой «30, 15, 7»: так их проще править, чем набором полей */
    public record RuleRequest(
            UUID ruleId,
            String name,
            NotificationTrigger triggerType,
            String daysBefore,
            Set<String> recipientEmails,
            Set<String> recipientRoles,
            Set<NotificationChannel> channels,
            UUID consentTypeId,
            UUID thirdPartyId) {}

    @PostMapping("/notifications/rules")
    public List<UiNotificationViewService.RuleRow> saveRule(@RequestBody RuleRequest request) {
        var form = new NotificationRuleService.RuleForm(
                request.name(),
                request.triggerType(),
                thresholds(request.daysBefore()),
                request.consentTypeId(),
                request.thirdPartyId(),
                request.recipientEmails() == null ? Set.of() : request.recipientEmails(),
                request.recipientRoles() == null ? Set.of() : request.recipientRoles(),
                request.channels() == null ? Set.of() : request.channels(),
                true);
        if (request.ruleId() == null) {
            rules.create(form);
        } else {
            rules.update(request.ruleId(), form);
        }
        return notificationView.rules();
    }

    @PostMapping("/notifications/rules/{id}/deactivate")
    public List<UiNotificationViewService.RuleRow> deactivateRule(@PathVariable UUID id) {
        rules.deactivate(id);
        return notificationView.rules();
    }

    /** @param truncated записей больше, чем показано: остальные видны после сужения фильтра */
    public record Journal(List<UiNotificationViewService.JournalRow> rows, long total) {}

    @GetMapping("/notifications/journal")
    public Journal journal(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) UUID ruleId,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = notificationView.journal(
                status,
                ruleId,
                channel,
                date(from),
                date(to),
                PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new Journal(found.getContent(), found.getTotalElements());
    }

    @GetMapping("/notifications/{id}")
    public UiNotificationViewService.MessageView message(@PathVariable UUID id) {
        return notificationView.message(id);
    }

    /** UI-13: повторная отправка уведомления со статусом «не отправлено». */
    @PostMapping("/notifications/{id}/retry")
    public Map<String, String> retry(@PathVariable UUID id) {
        notifications.retry(id);
        return Map.of("message", "Уведомление возвращено в очередь отправки");
    }

    /** UI-13: проверка почтового канала до того, как правило понадобится по-настоящему. */
    @PostMapping("/notifications/test-email")
    public Map<String, String> testEmail(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "");
        String error = testService.sendTestEmail(email);
        return error == null
                ? Map.of("message", "Тестовое письмо отправлено на " + email)
                : Map.of("error", "Письмо не отправлено: " + error);
    }

    /** Справочники раздела уведомлений: триггеры, каналы, статусы, типы согласий и партнёры. */
    @GetMapping("/notifications/options")
    public Map<String, Object> notificationOptions() {
        return Map.of(
                "triggers",
                        java.util.Arrays.stream(NotificationTrigger.values())
                                .map(trigger -> Map.of("code", trigger.name(), "nameRu", trigger.nameRu()))
                                .toList(),
                "channels",
                        java.util.Arrays.stream(NotificationChannel.values())
                                .map(channel -> Map.of("code", channel.name(), "nameRu", channel.nameRu()))
                                .toList(),
                "statuses",
                        java.util.Arrays.stream(NotificationStatus.values())
                                .map(status -> Map.of("code", status.name(), "nameRu", status.nameRu()))
                                .toList(),
                // Отбор правила: по типу согласия и по партнёру (FR-9.2) — без справочников их не выбрать.
                "consentTypes",
                        types.activeTypes().stream()
                                .map(type -> Map.of("code", type.getId().toString(), "nameRu", type.getNameRu()))
                                .toList(),
                "thirdParties",
                        thirdParties.list(org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
                                .map(party -> Map.of("code", party.getId().toString(), "nameRu", party.getName()))
                                .toList(),
                // Журнал фильтруется по правилу: список нужен экрану, а не только таблице.
                "rules",
                        rules.list().stream()
                                .map(rule -> Map.of("code", rule.getId().toString(), "nameRu", rule.getName()))
                                .toList(),
                "roles",
                        java.util.Arrays.stream(ru.example.inconsensu.common.domain.RoleCode.values())
                                .map(role -> Map.of("code", role.name(), "nameRu", role.nameRu()))
                                .toList());
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

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
