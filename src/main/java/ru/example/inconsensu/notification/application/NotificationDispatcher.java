package ru.example.inconsensu.notification.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.notification.domain.Notification;

/**
 * Отправляет накопленные уведомления (FR-9.2).
 *
 * <p>Работает вне транзакции постановки: письмо уходит по SMTP, и откат после успешной отправки означал бы
 * второе такое же письмо при следующем запуске.
 *
 * <p>Если по одному правилу для одного адресата накопилось больше {@code inconsensu.notification.digest-threshold}
 * уведомлений, вместо пачки писем уходит одно — с таблицей и CSV-вложением.
 */
@Component
public class NotificationDispatcher {

    public static final String DIGEST_THRESHOLD_SETTING = "inconsensu.notification.digest-threshold";
    private static final int DEFAULT_DIGEST_THRESHOLD = 20;

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {};

    private final NotificationService notifications;
    private final EmailSender emailSender;
    private final OperatorSettingsService settings;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public NotificationDispatcher(
            NotificationService notifications,
            EmailSender emailSender,
            OperatorSettingsService settings,
            ObjectMapper objectMapper,
            InConsensuProperties properties) {
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.batchSize = properties.notifications().batchSize();
    }

    @Scheduled(
            fixedDelayString = "${inconsensu.jobs.notification-dispatch.delay:PT1M}",
            initialDelayString = "${inconsensu.jobs.notification-dispatch.initial-delay:PT1M}")
    @SchedulerLock(name = "notificationDispatch", lockAtMostFor = "PT10M")
    public void dispatch() {
        int sent = dispatchNow();
        if (sent > 0) {
            LOG.info("Отправлено уведомлений: {}", sent);
        }
    }

    /** Вынесено отдельно, чтобы тест и тестовая отправка не ждали планировщик. */
    public int dispatchNow() {
        List<Notification> pending = notifications.pending(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }
        int threshold = digestThreshold();
        Map<String, List<Notification>> groups = pending.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getRuleId() + "|" + n.getRecipient(), LinkedHashMap::new, Collectors.toList()));

        int sent = 0;
        for (List<Notification> group : groups.values()) {
            sent += group.size() > threshold ? sendDigest(group) : sendIndividually(group);
        }
        return sent;
    }

    private int sendIndividually(List<Notification> group) {
        int sent = 0;
        for (Notification notification : group) {
            String error = emailSender.send(
                    notification.getRecipient(), notification.getSubjectLine(), notification.getBody());
            if (error == null) {
                notifications.markSent(List.of(notification.getId()));
                sent++;
            } else {
                notifications.markFailed(List.of(notification.getId()), error);
            }
        }
        return sent;
    }

    private int sendDigest(List<Notification> group) {
        Notification first = group.get(0);
        List<Map<String, Object>> rows = group.stream().map(this::dataOf).toList();
        String html =
                emailSender.render("digest", Map.of("rows", rows, "total", group.size(), "title", digestTitle(first)));
        List<UUID> ids = group.stream().map(Notification::getId).toList();

        String error = emailSender.sendWithAttachment(
                first.getRecipient(),
                digestTitle(first) + ": " + group.size(),
                html,
                "notifications.csv",
                toCsv(rows).getBytes(StandardCharsets.UTF_8));
        if (error == null) {
            notifications.markSent(ids);
            return group.size();
        }
        notifications.markFailed(ids, error);
        return 0;
    }

    private String digestTitle(Notification notification) {
        String subjectLine = notification.getSubjectLine();
        return subjectLine == null || subjectLine.isBlank() ? "Уведомления In consensu" : subjectLine;
    }

    private Map<String, Object> dataOf(Notification notification) {
        try {
            return objectMapper.readValue(notification.getData(), DATA_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Колонки CSV повторяют таблицу письма: получателю нужен тот же список, только машиночитаемый. */
    private String toCsv(List<Map<String, Object>> rows) {
        List<String> columns =
                List.of("subjectFullName", "subjectExternalId", "consentTypeName", "thirdPartyName", "validUntil");
        List<String> headers = List.of("ФИО", "Внешний идентификатор", "Тип согласия", "Третье лицо", "Действует до");

        StringBuilder builder = new StringBuilder(String.join(",", headers)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            columns.forEach(column -> cells.add(quote(row.get(column))));
            builder.append(String.join(",", cells)).append('\n');
        }
        return builder.toString();
    }

    private static String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\""
                : text;
    }

    private int digestThreshold() {
        try {
            return Integer.parseInt(settings.value(DIGEST_THRESHOLD_SETTING));
        } catch (RuntimeException e) {
            return DEFAULT_DIGEST_THRESHOLD;
        }
    }
}
