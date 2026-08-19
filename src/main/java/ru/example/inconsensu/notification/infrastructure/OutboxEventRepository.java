package ru.example.inconsensu.notification.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.notification.domain.OutboxEvent;
import ru.example.inconsensu.notification.domain.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Очередь на доставку. Порядок по времени создания сохраняет последовательность событий одного
     * согласия, чего требует FR-9.3.
     */
    @Query(
            """
            select e from OutboxEvent e
            where e.status in (ru.example.inconsensu.notification.domain.OutboxStatus.PENDING,
                               ru.example.inconsensu.notification.domain.OutboxStatus.RETRY)
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            order by e.createdAt, e.id
            """)
    List<OutboxEvent> findDue(@Param("now") Instant now, Pageable pageable);

    /**
     * Есть ли по тому же агрегату более раннее неотправленное событие (FR-9.3).
     *
     * <p>Порядок событий одного согласия обязан сохраняться, а событие в состоянии повтора в очередную
     * выборку не попадает — без этой проверки «отозвано» ушло бы получателю раньше «выдано».
     */
    @Query(
            """
            select count(e) from OutboxEvent e
            where e.aggregateType = :aggregateType and e.aggregateId = :aggregateId
              and e.status in (ru.example.inconsensu.notification.domain.OutboxStatus.PENDING,
                               ru.example.inconsensu.notification.domain.OutboxStatus.RETRY)
              and e.createdAt < :createdAt
            """)
    long countEarlierUnsent(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("createdAt") Instant createdAt);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, String aggregateId);

    java.util.List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    org.springframework.data.domain.Page<OutboxEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(OutboxStatus status);
}
