package ru.example.cus.audit.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.domain.AuditAnchor;
import ru.example.cus.audit.domain.AuditEvent;
import ru.example.cus.audit.domain.AuditHashCalculator;
import ru.example.cus.audit.infrastructure.AuditAnchorRepository;
import ru.example.cus.audit.infrastructure.AuditEventRepository;
import ru.example.cus.common.config.CusProperties;

/**
 * Writes the daily anchor of the audit journal (FR-10.1).
 *
 * <p>Runs shortly after midnight in the operator timezone, once per cluster thanks to ShedLock (NFR-2). An anchor is
 * written once and never rewritten: the table is append-only (FR-10.2).
 */
@Component
public class AuditAnchorJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuditAnchorJob.class);

    private final AuditEventRepository eventRepository;
    private final AuditAnchorRepository anchorRepository;
    private final CusProperties properties;
    private final Clock clock;

    public AuditAnchorJob(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            CusProperties properties,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.anchorRepository = anchorRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${cus.jobs.audit-anchor.cron:0 10 0 * * *}", zone = "${cus.timezone:Europe/Moscow}")
    @SchedulerLock(name = "auditAnchor", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void anchorPreviousDay() {
        LocalDate yesterday =
                LocalDate.ofInstant(clock.instant(), properties.timezone()).minusDays(1);
        createAnchor(yesterday);
    }

    /** Extracted so that tests can anchor an arbitrary day without waiting for the cron. */
    @Transactional
    public void createAnchor(LocalDate day) {
        if (anchorRepository.existsById(day)) {
            LOG.debug("Якорь аудита за {} уже существует", day);
            return;
        }
        List<AuditEvent> events = eventRepository.findDay(
                day.atStartOfDay(properties.timezone()).toInstant(),
                day.plusDays(1).atStartOfDay(properties.timezone()).toInstant());

        String dayHash = AuditHashCalculator.dayHash(
                events.stream().map(AuditEvent::getHash).toList());
        anchorRepository.save(new AuditAnchor(day, events.size(), dayHash, clock.instant()));
        LOG.info("Записан якорь аудита за {}: событий {}", day, events.size());
    }
}
