package ru.example.cus.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.iam.application.UserService;
import ru.example.cus.notification.domain.Notification;
import ru.example.cus.notification.domain.NotificationChannel;
import ru.example.cus.notification.domain.NotificationRule;
import ru.example.cus.notification.domain.NotificationStatus;
import ru.example.cus.notification.infrastructure.NotificationRepository;

/**
 * Очередь уведомлений: постановка с дедупликацией и учёт результата отправки (FR-9.1, FR-9.2).
 *
 * <p>Отправкой занимается {@link NotificationDispatcher}: письмо уходит вне транзакции, потому что откат
 * после успешного SMTP-обмена вернул бы запись в очередь и клиент получил бы письмо дважды.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final UserService users;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationService(
            NotificationRepository notifications, UserService users, ObjectMapper objectMapper, Clock clock) {
        this.notifications = notifications;
        this.users = users;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Адресаты правила: явные адреса плюс адреса всех активных пользователей указанных ролей (FR-9.2). */
    @Transactional(readOnly = true)
    public Set<String> recipientsOf(NotificationRule rule) {
        Set<String> recipients = new LinkedHashSet<>(rule.getRecipientEmails());
        recipients.addAll(users.emailsByRoles(rule.getRecipientRoles()));
        recipients.removeIf(email -> email == null || email.isBlank());
        return recipients;
    }

    /**
     * Ставит уведомление в очередь.
     *
     * <p>Новая транзакция и перехват нарушения уникальности — это и есть дедупликация FR-9.1: при
     * повторном запуске задачи или параллельной работе двух экземпляров второе уведомление отсекается
     * базой, а не проверкой «уже есть», которая в гонке не помогает.
     *
     * @return {@code true}, если уведомление поставлено, и {@code false}, если такое уже существует
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueue(
            UUID ruleId,
            UUID consentId,
            UUID subjectId,
            String dedupeKey,
            NotificationChannel channel,
            String recipient,
            String subjectLine,
            String body,
            Map<String, Object> data) {
        if (notifications.existsByDedupeKey(dedupeKey)) {
            return false;
        }
        try {
            notifications.saveAndFlush(new Notification(
                    UUID.randomUUID(),
                    ruleId,
                    consentId,
                    subjectId,
                    dedupeKey,
                    channel,
                    recipient,
                    subjectLine,
                    body,
                    toJson(data),
                    clock.instant()));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Отметка о том, что событие ушло в outbox (FR-9.3).
     *
     * <p>Запись создаётся сразу отправленной: доставку webhook ведёт outbox со своим журналом, а здесь
     * нужен только след и тот же ключ дедупликации, что и у писем, — иначе повторный запуск задачи
     * положил бы в outbox второе такое же событие.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueueWebhook(
            UUID ruleId,
            UUID consentId,
            UUID subjectId,
            String dedupeKey,
            String subjectLine,
            Map<String, Object> data) {
        if (notifications.existsByDedupeKey(dedupeKey)) {
            return false;
        }
        try {
            Notification notification = new Notification(
                    UUID.randomUUID(),
                    ruleId,
                    consentId,
                    subjectId,
                    dedupeKey,
                    NotificationChannel.WEBHOOK,
                    "outbox",
                    subjectLine,
                    null,
                    toJson(data),
                    clock.instant());
            notification.markSent(clock.instant());
            notifications.saveAndFlush(notification);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> pending(int limit) {
        return notifications.findByStatusOrderByCreatedAtAsc(NotificationStatus.PENDING, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(Pageable pageable) {
        return notifications.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Notification get(UUID id) {
        return notifications.findById(id).orElseThrow(() -> ApiException.notFound("Уведомление не найдено"));
    }

    /**
     * Повторная отправка неудавшегося уведомления (UI-13).
     *
     * <p>Запись возвращается в очередь, а письмо уходит обычным путём через {@code NotificationDispatcher}:
     * второй путь отправки означал бы второй набор ошибок и вторую копию письма.
     */
    @Transactional
    public Notification retry(UUID id) {
        Notification notification = get(id);
        notification.requeue();
        return notifications.save(notification);
    }

    @Transactional
    public void markSent(Collection<UUID> ids) {
        notifications.findAllById(ids).forEach(notification -> {
            notification.markSent(clock.instant());
            notifications.save(notification);
        });
    }

    @Transactional
    public void markFailed(Collection<UUID> ids, String error) {
        notifications.findAllById(ids).forEach(notification -> {
            notification.markFailed(error);
            notifications.save(notification);
        });
    }

    private String toJson(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать данные уведомления", e);
        }
    }
}
