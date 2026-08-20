package ru.example.inconsensu.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.notification.domain.WebhookDelivery;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findBySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId, Pageable pageable);

    /** Последняя доставка подписки: её результат UI-14 требует в списке подписок. */
    java.util.Optional<WebhookDelivery> findFirstBySubscriptionIdOrderByDeliveredAtDesc(UUID subscriptionId);

    List<WebhookDelivery> findByOutboxEventIdOrderByAttemptAsc(UUID outboxEventId);
}
