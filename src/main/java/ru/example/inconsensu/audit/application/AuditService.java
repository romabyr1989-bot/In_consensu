package ru.example.inconsensu.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.domain.AuditEvent;
import ru.example.inconsensu.audit.domain.AuditHashCalculator;
import ru.example.inconsensu.audit.infrastructure.AuditEventRepository;
import ru.example.inconsensu.common.domain.ActorType;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.security.CurrentUser;

/**
 * Appends events to the immutable journal (FR-10.1).
 *
 * <p>Always joins the caller's transaction: an audit record and the change it describes are committed together or not
 * at all (FR-4.4). Payloads must carry codes and identifiers only - never personal data (NFR-3).
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public AuditEvent record(
            String aggregateType, String aggregateId, AuditEventType eventType, Map<String, ?> payload) {
        return record(aggregateType, aggregateId, null, eventType, payload);
    }

    @Transactional
    public AuditEvent record(
            String aggregateType,
            String aggregateId,
            UUID subjectId,
            AuditEventType eventType,
            Map<String, ?> payload) {
        lockAggregate(aggregateType, aggregateId);

        String prevHash = repository
                .findFirstByAggregateTypeAndAggregateIdOrderByIdDesc(aggregateType, aggregateId)
                .map(AuditEvent::getHash)
                .orElse(null);

        Instant occurredAt = clock.instant();
        ActorType actorType = currentActorType();
        String actorId = CurrentUser.login();
        String payloadJson = AuditHashCalculator.toJson(payload);
        String hash = AuditHashCalculator.hash(
                prevHash,
                aggregateType,
                aggregateId,
                subjectId,
                eventType,
                occurredAt,
                actorType,
                actorId,
                payloadJson);

        return repository.save(new AuditEvent(
                aggregateType,
                aggregateId,
                subjectId,
                eventType,
                occurredAt,
                actorType,
                actorId,
                payloadJson,
                prevHash,
                hash));
    }

    public List<AuditEvent> historyOf(String aggregateType, String aggregateId) {
        return repository.findByAggregateTypeAndAggregateIdOrderByIdAsc(aggregateType, aggregateId);
    }

    public List<AuditEvent> historyOfSubject(UUID subjectId) {
        return repository.findBySubjectIdOrderByIdAsc(subjectId);
    }

    /**
     * Serialises appends to one aggregate.
     *
     * <p>Two concurrent transactions reading the same tail would otherwise write two events with the same
     * {@code prev_hash} and fork the chain. The lock is per aggregate, so unrelated aggregates never wait for each
     * other, and it is released when the transaction ends.
     */
    private void lockAggregate(String aggregateType, String aggregateId) {
        long key = lockKey(aggregateType + ':' + aggregateId);
        jdbcTemplate.query("select pg_advisory_xact_lock(?)", resultSet -> null, key);
    }

    private static long lockKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long key = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                key = (key << 8) | (digest[i] & 0xffL);
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить ключ блокировки агрегата", e);
        }
    }

    private static ActorType currentActorType() {
        if (CurrentUser.SYSTEM_LOGIN.equals(CurrentUser.login())) {
            return ActorType.SYSTEM;
        }
        return CurrentUser.roles().contains(RoleCode.INTEGRATION.name()) ? ActorType.INTEGRATION : ActorType.USER;
    }
}
