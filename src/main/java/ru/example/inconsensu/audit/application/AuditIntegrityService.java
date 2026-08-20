package ru.example.inconsensu.audit.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.domain.AuditAnchor;
import ru.example.inconsensu.audit.domain.AuditEvent;
import ru.example.inconsensu.audit.domain.AuditHashCalculator;
import ru.example.inconsensu.audit.infrastructure.AuditAnchorRepository;
import ru.example.inconsensu.audit.infrastructure.AuditEventRepository;
import ru.example.inconsensu.common.config.InConsensuProperties;

/** Recomputes the hash chains and the daily anchors and reports the first divergence (FR-10.3, FR-10.4). */
@Service
public class AuditIntegrityService {

    /** Outcome shown to the auditor; {@code BROKEN} means a row was altered outside the application. */
    public enum Integrity {
        OK,
        BROKEN
    }

    public record Problem(String aggregateType, String aggregateId, Long eventId, String description) {}

    public record Report(
            Integrity integrity,
            long aggregatesChecked,
            long eventsChecked,
            long anchorsChecked,
            List<Problem> problems) {}

    private final AuditEventRepository eventRepository;
    private final AuditAnchorRepository anchorRepository;
    private final ZoneId operatorZone;

    public AuditIntegrityService(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            InConsensuProperties properties) {
        this.eventRepository = eventRepository;
        this.anchorRepository = anchorRepository;
        this.operatorZone = properties.timezone();
    }

    /** Verifies the chain of a single aggregate - used by the consent dossier of FR-10.3. */
    @Transactional(readOnly = true)
    public Report verifyAggregate(String aggregateType, String aggregateId) {
        List<Problem> problems = new ArrayList<>();
        long events = verifyChain(aggregateType, aggregateId, problems);
        return new Report(problems.isEmpty() ? Integrity.OK : Integrity.BROKEN, 1, events, 0, problems);
    }

    /**
     * Проверка всех цепочек, затронувших субъекта (UI-4).
     *
     * <p>Цепочка ведётся по агрегату, а у клиента их несколько — по одному на согласие. Кнопка на вкладке
     * «История» должна отвечать за всю историю клиента, поэтому проверяются все его агрегаты сразу.
     */
    @Transactional(readOnly = true)
    public Report verifySubject(java.util.UUID subjectId) {
        List<Problem> problems = new ArrayList<>();
        long events = 0;
        long aggregates = 0;
        for (Object[] aggregate : eventRepository.findDistinctAggregatesOfSubject(subjectId)) {
            aggregates++;
            events += verifyChain((String) aggregate[0], (String) aggregate[1], problems);
        }
        return new Report(problems.isEmpty() ? Integrity.OK : Integrity.BROKEN, aggregates, events, 0, problems);
    }

    /** Full verification of every chain and every anchor (FR-10.4). */
    @Transactional(readOnly = true)
    public Report verifyAll() {
        List<Problem> problems = new ArrayList<>();
        long aggregates = 0;
        long events = 0;

        for (Object[] aggregate : eventRepository.findDistinctAggregates()) {
            aggregates++;
            events += verifyChain((String) aggregate[0], (String) aggregate[1], problems);
        }

        List<AuditAnchor> anchors = anchorRepository.findAllByOrderByDayAsc();
        for (AuditAnchor anchor : anchors) {
            verifyAnchor(anchor, problems);
        }

        return new Report(
                problems.isEmpty() ? Integrity.OK : Integrity.BROKEN, aggregates, events, anchors.size(), problems);
    }

    private long verifyChain(String aggregateType, String aggregateId, List<Problem> problems) {
        List<AuditEvent> chain =
                eventRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(aggregateType, aggregateId);
        String expectedPrevHash = null;

        for (AuditEvent event : chain) {
            if (!Objects.equals(expectedPrevHash, event.getPrevHash())) {
                problems.add(new Problem(
                        aggregateType,
                        aggregateId,
                        event.getId(),
                        "Разрыв цепочки: prev_hash не совпадает с предыдущим событием"));
                return chain.size();
            }
            String recomputed = AuditHashCalculator.hash(
                    event.getPrevHash(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getSubjectId(),
                    event.getEventType(),
                    event.getOccurredAt(),
                    event.getActorType(),
                    event.getActorId(),
                    event.getPayload());
            if (!recomputed.equals(event.getHash())) {
                problems.add(
                        new Problem(aggregateType, aggregateId, event.getId(), "Событие изменено: хеш не сходится"));
                return chain.size();
            }
            expectedPrevHash = event.getHash();
        }
        return chain.size();
    }

    private void verifyAnchor(AuditAnchor anchor, List<Problem> problems) {
        LocalDate day = anchor.getDay();
        List<AuditEvent> events = eventRepository.findDay(
                day.atStartOfDay(operatorZone).toInstant(),
                day.plusDays(1).atStartOfDay(operatorZone).toInstant());

        if (events.size() != anchor.getEventsCount()) {
            problems.add(new Problem(
                    "audit_anchor",
                    day.toString(),
                    null,
                    "Число событий за день не совпадает с якорем: в журнале " + events.size() + ", в якоре "
                            + anchor.getEventsCount()));
            return;
        }
        String recomputed = AuditHashCalculator.dayHash(
                events.stream().map(AuditEvent::getHash).toList());
        if (!recomputed.equals(anchor.getDayHash())) {
            problems.add(new Problem("audit_anchor", day.toString(), null, "Хеш дня не сходится с якорем"));
        }
    }
}
