package ru.example.inconsensu.ui.application;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditQueryService;
import ru.example.inconsensu.iam.application.UserService;

/**
 * Модель экранов аудита (UI-15).
 *
 * <p>Журнал доступа к ПДн печатал идентификатор пользователя как есть — то есть колонка «кто» была
 * строкой UUID, а после входа через форму и вовсе пустой (см. {@code AppUserPrincipal}). Здесь
 * идентификатор превращается в имя сотрудника, а даты — в формат UI-0.4.
 */
@Service
public class UiAuditViewService {

    /** @param user имя сотрудника; «—», если запись оставила система или учётной записи уже нет */
    public record AccessRow(String occurredAt, String user, String endpoint, String subjectId, String requestId) {}

    public record EventRow(
            String occurredAt,
            String aggregateType,
            String aggregateId,
            String eventTypeRu,
            String actor,
            String payload) {}

    private final AuditQueryService queries;
    private final UserService users;
    private final UiFormats formats;

    public UiAuditViewService(AuditQueryService queries, UserService users, UiFormats formats) {
        this.queries = queries;
        this.users = users;
        this.formats = formats;
    }

    @Transactional(readOnly = true)
    public Page<AccessRow> accessLog(AuditQueryService.AccessFilter filter, Pageable pageable) {
        return queries.accessLog(filter, pageable)
                .map(entry -> new AccessRow(
                        formats.dateTime(entry.getOccurredAt()),
                        userName(entry.getUserId()),
                        entry.getEndpoint(),
                        entry.getSubjectId() == null ? "" : entry.getSubjectId().toString(),
                        entry.getRequestId()));
    }

    @Transactional(readOnly = true)
    public Page<EventRow> events(AuditQueryService.EventFilter filter, Pageable pageable) {
        return queries.events(filter, pageable)
                .map(event -> new EventRow(
                        formats.dateTime(event.getOccurredAt()),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType().nameRu(),
                        event.getActorId(),
                        event.getPayload()));
    }

    private String userName(UUID userId) {
        if (userId == null) {
            return "—";
        }
        return users.displayName(userId).filter(name -> !name.isBlank()).orElseGet(userId::toString);
    }
}
