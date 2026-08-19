package ru.example.cus.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.notification.domain.NotificationRule;
import ru.example.cus.notification.domain.NotificationTrigger;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    List<NotificationRule> findByActiveTrueAndTriggerTypeOrderByNameAsc(NotificationTrigger triggerType);

    List<NotificationRule> findAllByOrderByNameAsc();
}
