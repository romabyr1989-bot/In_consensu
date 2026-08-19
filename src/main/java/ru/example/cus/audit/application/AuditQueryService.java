package ru.example.cus.audit.application;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.domain.AuditEvent;
import ru.example.cus.audit.domain.PdnAccessLogEntry;
import ru.example.cus.audit.infrastructure.AuditEventRepository;
import ru.example.cus.audit.infrastructure.PdnAccessLogRepository;
import ru.example.cus.common.domain.AuditEventType;

/**
 * Чтение журналов с фильтрами (FR-10.1, FR-10.5, UI-15).
 *
 * <p>Один набор фильтров на REST и на экран: если бы интерфейс собирал условия сам, «журнал» в API и в
 * интерфейсе показывал бы разное, а сверять было бы нечем.
 */
@Service
public class AuditQueryService {

    public record EventFilter(
            String aggregateType,
            String aggregateId,
            AuditEventType eventType,
            String actorId,
            UUID subjectId,
            Instant from,
            Instant to) {

        public static EventFilter empty() {
            return new EventFilter(null, null, null, null, null, null, null);
        }
    }

    public record AccessFilter(UUID userId, UUID subjectId, String endpoint, Instant from, Instant to) {

        public static AccessFilter empty() {
            return new AccessFilter(null, null, null, null, null);
        }
    }

    private final AuditEventRepository events;
    private final PdnAccessLogRepository accessLog;

    public AuditQueryService(AuditEventRepository events, PdnAccessLogRepository accessLog) {
        this.events = events;
        this.accessLog = accessLog;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> events(EventFilter filter, Pageable pageable) {
        Specification<AuditEvent> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.aggregateType() != null) {
                predicates.add(builder.equal(root.get("aggregateType"), filter.aggregateType()));
            }
            if (filter.aggregateId() != null) {
                predicates.add(builder.equal(root.get("aggregateId"), filter.aggregateId()));
            }
            if (filter.eventType() != null) {
                predicates.add(builder.equal(root.get("eventType"), filter.eventType()));
            }
            if (filter.actorId() != null) {
                predicates.add(builder.equal(root.get("actorId"), filter.actorId()));
            }
            if (filter.subjectId() != null) {
                predicates.add(builder.equal(root.get("subjectId"), filter.subjectId()));
            }
            if (filter.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(builder.lessThan(root.get("occurredAt"), filter.to()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return events.findAll(specification, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PdnAccessLogEntry> accessLog(AccessFilter filter, Pageable pageable) {
        Specification<PdnAccessLogEntry> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.userId() != null) {
                predicates.add(builder.equal(root.get("userId"), filter.userId()));
            }
            if (filter.subjectId() != null) {
                predicates.add(builder.equal(root.get("subjectId"), filter.subjectId()));
            }
            if (filter.endpoint() != null) {
                predicates.add(builder.like(root.get("endpoint"), filter.endpoint() + "%"));
            }
            if (filter.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(builder.lessThan(root.<Instant>get("occurredAt"), filter.to()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return accessLog.findAll(specification, pageable);
    }
}
