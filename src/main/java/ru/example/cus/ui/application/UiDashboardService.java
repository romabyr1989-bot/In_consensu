package ru.example.cus.ui.application;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.catalog.application.CatalogStatsService;
import ru.example.cus.integration.application.ConsentImportService;
import ru.example.cus.integration.domain.ImportJob;
import ru.example.cus.notification.application.NotificationService;
import ru.example.cus.notification.application.OutboxQueryService;
import ru.example.cus.notification.domain.Notification;
import ru.example.cus.notification.domain.OutboxEvent;

/**
 * Данные дашборда (UI-2).
 *
 * <p>Отдельный сервис, а не сборка в контроллере: числа обязаны совпадать с ответом `/catalog/stats`,
 * и брать их нужно из того же источника.
 */
@Service
public class UiDashboardService {

    /** UI-2: «Последние уведомления» — десять записей. */
    private static final int RECENT_LIMIT = 10;

    private final CatalogStatsService stats;
    private final NotificationService notifications;
    private final OutboxQueryService outbox;
    private final ConsentImportService imports;

    public UiDashboardService(
            CatalogStatsService stats,
            NotificationService notifications,
            OutboxQueryService outbox,
            ConsentImportService imports) {
        this.stats = stats;
        this.notifications = notifications;
        this.outbox = outbox;
        this.imports = imports;
    }

    @Transactional(readOnly = true)
    public CatalogStatsService.CatalogStats stats() {
        return stats.stats();
    }

    @Transactional(readOnly = true)
    public List<Notification> recentNotifications() {
        return notifications.list(PageRequest.of(0, RECENT_LIMIT)).getContent();
    }

    /** Блок администратора «Проблемы доставки webhook» (UI-2). */
    @Transactional(readOnly = true)
    public List<OutboxEvent> failedDeliveries() {
        return outbox.failed(RECENT_LIMIT);
    }

    /** Блок администратора «Ошибки импорта» (UI-2). */
    @Transactional(readOnly = true)
    public List<ImportJob> failedImports() {
        return imports.list(PageRequest.of(0, RECENT_LIMIT)).getContent().stream()
                .filter(job -> job.getRejected() > 0 || job.getStatus().name().equals("FAILED"))
                .toList();
    }
}
