package ru.example.cus.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.notification.domain.WebhookSubscription;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByActiveTrueOrderByNameAsc();

    List<WebhookSubscription> findAllByOrderByNameAsc();
}
