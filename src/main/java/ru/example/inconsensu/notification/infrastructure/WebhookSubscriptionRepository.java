package ru.example.inconsensu.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.notification.domain.WebhookSubscription;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByActiveTrueOrderByNameAsc();

    List<WebhookSubscription> findAllByOrderByNameAsc();
}
