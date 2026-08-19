package ru.example.cus.registry.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.infrastructure.ConsentRepository;

/**
 * Ежедневная материализация статусов согласий (FR-5.3).
 *
 * <p>Колонка {@code status} нужна фильтрам и отчётам, но истечение срока наступает само по себе, без единой
 * операции над строкой — поэтому раз в сутки статусы догоняются задачей. Расчётное правило то же самое, что и
 * при чтении, и это закреплено тестом.
 */
@Component
public class ConsentStatusJob {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentStatusJob.class);

    /** Запас сверх порога «заканчивается»: берём с полем, чтобы не пропустить пограничные согласия. */
    private static final Duration LOOKAHEAD_MARGIN = Duration.ofDays(1);

    private final ConsentRepository consents;
    private final ConsentQueryService queryService;
    private final Clock clock;

    public ConsentStatusJob(ConsentRepository consents, ConsentQueryService queryService, Clock clock) {
        this.consents = consents;
        this.queryService = queryService;
        this.clock = clock;
    }

    @Scheduled(cron = "${cus.jobs.consent-status.cron:0 30 0 * * *}", zone = "${cus.timezone:Europe/Moscow}")
    @SchedulerLock(name = "consentStatusRefresh", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void refreshStatuses() {
        int updated = refreshNow();
        LOG.info("Материализация статусов согласий: обновлено {}", updated);
    }

    /** Вынесено отдельно, чтобы тест мог выполнить пересчёт, не дожидаясь cron. */
    @Transactional
    public int refreshNow() {
        int expiringDays = queryService.expiringDays();
        var horizon = clock.instant().plus(Duration.ofDays(expiringDays)).plus(LOOKAHEAD_MARGIN);
        List<Consent> candidates = consents.findForStatusRefresh(horizon);

        int updated = 0;
        for (Consent consent : candidates) {
            if (consent.refreshStatus(clock.instant(), expiringDays)) {
                consents.save(consent);
                updated++;
            }
        }
        return updated;
    }
}
