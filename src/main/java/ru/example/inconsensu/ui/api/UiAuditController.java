package ru.example.inconsensu.ui.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.inconsensu.audit.application.AuditQueryService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.ui.application.UiAuditViewService;
import ru.example.inconsensu.ui.application.UiSorting;

/** UI-15: события аудита, журнал доступа к ПДн и проверка целостности. */
@Controller
@PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
public class UiAuditController {

    /** UI-0.8: размеры страницы фиксированы — 20, 50 или 100. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditQueryService queries;
    private final UiAuditViewService view;
    private final ru.example.inconsensu.audit.application.AuditVerificationService verifications;
    private final ZoneId zone;

    public UiAuditController(
            AuditQueryService queries,
            ru.example.inconsensu.audit.application.AuditVerificationService verifications,
            UiAuditViewService view,
            java.time.Clock clock) {
        this.queries = queries;
        this.verifications = verifications;
        this.view = view;
        this.zone = clock.getZone();
    }

    @GetMapping("/ui/audit")
    public String events(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        model.addAttribute(
                "events",
                view.events(
                        new AuditQueryService.EventFilter(
                                blankToNull(aggregateType),
                                null,
                                eventType,
                                blankToNull(actorId),
                                subjectId,
                                from == null ? null : from.atStartOfDay(zone).toInstant(),
                                to == null
                                        ? null
                                        : to.plusDays(1).atStartOfDay(zone).toInstant()),
                        pageRequest(page, size, sort, direction, EVENT_SORT)));
        model.addAttribute("eventTypes", AuditEventType.values());
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        // Выбранные фильтры возвращаются в форму: иначе поля пусты, а таблица отфильтрована (UI-0.8).
        model.addAttribute("selectedAggregateType", aggregateType);
        model.addAttribute("selectedEventType", eventType);
        model.addAttribute("selectedActorId", actorId);
        model.addAttribute("selectedSubjectId", subjectId);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
        model.addAttribute("pageSize", normalizeSize(size));
        return "ui/audit/events";
    }

    @GetMapping("/ui/audit/access-log")
    public String accessLog(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            Model model) {
        model.addAttribute(
                "entries",
                view.accessLog(
                        new AuditQueryService.AccessFilter(
                                null,
                                subjectId,
                                blankToNull(endpoint),
                                from == null ? null : from.atStartOfDay(zone).toInstant(),
                                to == null
                                        ? null
                                        : to.plusDays(1).atStartOfDay(zone).toInstant()),
                        pageRequest(page, size, sort, direction, ACCESS_SORT)));
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("selectedSubjectId", subjectId);
        model.addAttribute("selectedEndpoint", endpoint);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
        model.addAttribute("pageSize", normalizeSize(size));
        return "ui/audit/access-log";
    }

    /** UI-0.8: колонки вкладки «События», по которым разрешена сортировка. */
    private static final java.util.Map<String, String> EVENT_SORT = java.util.Map.of(
            "occurredAt", "occurredAt", "aggregate", "aggregateType", "eventType", "eventType", "actor", "actorId");

    /** UI-0.8: колонки вкладки «Доступ к ПДн». */
    private static final java.util.Map<String, String> ACCESS_SORT =
            java.util.Map.of("occurredAt", "occurredAt", "endpoint", "endpoint", "user", "userId");

    private static PageRequest pageRequest(
            int page, int size, String sort, String direction, java.util.Map<String, String> allowed) {
        Sort order = UiSorting.of(sort, direction, allowed, Sort.by(Sort.Direction.DESC, "id"));
        return PageRequest.of(Math.max(page, 0), normalizeSize(size), order);
    }

    private static int normalizeSize(int size) {
        return size == 50 || size == 100 ? size : DEFAULT_PAGE_SIZE;
    }

    @GetMapping("/ui/audit/integrity")
    public String integrity(Model model) {
        model.addAttribute("verifications", verifications.history());
        return "ui/audit/integrity";
    }

    /** Проверка запускается в фоне: аудитор сразу видит запись «выполняется» и обновляет страницу. */
    @PostMapping("/ui/audit/integrity")
    public String verify(Model model) {
        verifications.start();
        return "redirect:/ui/audit/integrity";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
