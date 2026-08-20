package ru.example.inconsensu.audit.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.audit.domain.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    /** Tail of the chain of one aggregate; its hash becomes {@code prev_hash} of the next event (FR-10.1). */
    Optional<AuditEvent> findFirstByAggregateTypeAndAggregateIdOrderByIdDesc(String aggregateType, String aggregateId);

    List<AuditEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, String aggregateId);

    List<AuditEvent> findBySubjectIdOrderByIdAsc(java.util.UUID subjectId);

    @Query("select e from AuditEvent e where e.occurredAt >= :from and e.occurredAt < :to order by e.id")
    List<AuditEvent> findDay(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select distinct e.aggregateType, e.aggregateId from AuditEvent e")
    List<Object[]> findDistinctAggregates();

    /** Агрегаты, затронувшие субъекта: их цепочки проверяет вкладка «История» карточки (UI-4). */
    @Query("select distinct e.aggregateType, e.aggregateId from AuditEvent e where e.subjectId = :subjectId")
    List<Object[]> findDistinctAggregatesOfSubject(@Param("subjectId") java.util.UUID subjectId);
}
