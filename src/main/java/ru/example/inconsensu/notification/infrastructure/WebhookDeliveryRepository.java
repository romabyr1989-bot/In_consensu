package ru.example.inconsensu.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.notification.domain.WebhookDelivery;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findBySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId, Pageable pageable);

    List<WebhookDelivery> findByOutboxEventIdOrderByAttemptAsc(UUID outboxEventId);
}
