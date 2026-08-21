package ru.example.inconsensu.notification.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.notification.domain.Notification;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByDedupeKey(String dedupeKey);

    /** NFR-6: сколько уведомлений не удалось отправить — метрика дежурного. */
    long countByStatus(NotificationStatus status);

    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status, Pageable pageable);

    /**
     * Сколько уведомлений правила ждёт отправки этому получателю (FR-9.2).
     *
     * <p>Решение «дайджест или отдельные письма» принимается по всей очереди правила, а не по порции: при
     * очереди больше порции получатель получал и дайджест, и отдельные письма за тот же день.
     */
    long countByStatusAndRuleIdAndRecipient(NotificationStatus status, UUID ruleId, String recipient);

    List<Notification> findByStatusAndRuleIdAndRecipientOrderByCreatedAtAsc(
            NotificationStatus status, UUID ruleId, String recipient, Pageable pageable);

    Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Notification> findByConsentIdOrderByCreatedAtDesc(UUID consentId);

    /**
     * Журнал с фильтрами UI-13: дата, статус, правило и канал.
     *
     * <p>Фильтрация запросом, а не в памяти: иначе пагинация и счётчик страниц врут — на этом уже
     * обжигались в каталоге форм (ADR-0051).
     */
    @Query("select n from Notification n where (:status is null or n.status = :status) "
            + "and (:ruleId is null or n.ruleId = :ruleId) "
            + "and (:channel is null or n.channel = :channel) "
            + "and n.createdAt >= :from and n.createdAt < :to order by n.createdAt desc")
    Page<Notification> search(
            @Param("status") NotificationStatus status,
            @Param("ruleId") UUID ruleId,
            @Param("channel") NotificationChannel channel,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
