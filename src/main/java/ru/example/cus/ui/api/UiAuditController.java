package ru.example.cus.ui.api;

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
import ru.example.cus.audit.application.AuditQueryService;
import ru.example.cus.common.domain.AuditEventType;

/** UI-15: события аудита, журнал доступа к ПДн и проверка целостности. */
@Controller
@PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
public class UiAuditController {

    private static final int PAGE_SIZE = 50;

    private final AuditQueryService queries;
    private final ru.example.cus.audit.application.AuditVerificationService verifications;
    private final ZoneId zone;

    public UiAuditController(
            AuditQueryService queries,
            ru.example.cus.audit.application.AuditVerificationService verifications,
            java.time.Clock clock) {
        this.queries = queries;
        this.verifications = verifications;
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
            Model model) {
        model.addAttribute(
                "events",
                queries.events(
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
                        PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))));
        model.addAttribute("eventTypes", AuditEventType.values());
        model.addAttribute("filter", new Object[] {aggregateType, eventType, actorId, subjectId, from, to});
        return "ui/audit/events";
    }

    @GetMapping("/ui/audit/access-log")
    public String accessLog(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        model.addAttribute(
                "entries",
                queries.accessLog(
                        new AuditQueryService.AccessFilter(null, subjectId, blankToNull(endpoint), null, null),
                        PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))));
        return "ui/audit/access-log";
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
