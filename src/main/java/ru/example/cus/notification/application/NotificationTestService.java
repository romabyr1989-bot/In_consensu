package ru.example.cus.notification.application;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.example.cus.common.security.CurrentUser;

/**
 * Ручные операции с уведомлениями: тестовое письмо и внеочередной прогон задачи (FR-9.5).
 *
 * <p>Отдельный сервис, чтобы контроллер не зависел ни от планировщика, ни от почтового транспорта.
 */
@Service
public class NotificationTestService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** @param dispatched сколько писем удалось отправить сразу после прогона */
    public record RunResult(int expiring, int expired, int contracts, int dispatched) {}

    private final NotificationJob job;
    private final NotificationDispatcher dispatcher;
    private final EmailSender emailSender;
    private final ZoneId zone;
    private final Clock clock;

    public NotificationTestService(
            NotificationJob job, NotificationDispatcher dispatcher, EmailSender emailSender, Clock clock) {
        this.job = job;
        this.dispatcher = dispatcher;
        this.emailSender = emailSender;
        this.zone = clock.getZone();
        this.clock = clock;
    }

    /** @return {@code null} при успехе, иначе текст ошибки отправки */
    public String sendTestEmail(String email) {
        String html = emailSender.render(
                "test", Map.of("sentAt", TIMESTAMP.format(clock.instant().atZone(zone)), "actor", CurrentUser.login()));
        return emailSender.send(email, "ЦУС: проверка отправки писем", html);
    }

    public RunResult runNow() {
        NotificationJob.ScanResult scan = job.scanNow();
        int dispatched = dispatcher.dispatchNow();
        return new RunResult(scan.expiring(), scan.expired(), scan.contracts(), dispatched);
    }
}
