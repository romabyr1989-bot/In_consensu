package ru.example.inconsensu.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.notification.domain.Notification;
import ru.example.inconsensu.notification.domain.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByDedupeKey(String dedupeKey);

    /** NFR-6: сколько уведомлений не удалось отправить — метрика дежурного. */
    long countByStatus(NotificationStatus status);

    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status, Pageable pageable);

    Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Notification> findByConsentIdOrderByCreatedAtDesc(UUID consentId);
}
