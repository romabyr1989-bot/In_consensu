package ru.example.inconsensu.notification.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationTrigger;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    List<NotificationRule> findByActiveTrueAndTriggerTypeOrderByNameAsc(NotificationTrigger triggerType);

    List<NotificationRule> findAllByOrderByNameAsc();
}
