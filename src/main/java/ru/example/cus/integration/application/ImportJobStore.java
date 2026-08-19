package ru.example.cus.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.domain.CusEvent;
import ru.example.cus.common.domain.EventTypes;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.integration.domain.ImportJob;
import ru.example.cus.integration.infrastructure.ImportJobRepository;

/**
 * Состояние задачи импорта в базе (FR-4.5, FR-9.4).
 *
 * <p>Отдельный бин, а не приватные методы {@link ConsentImportService}: {@code @Transactional} работает
 * только через прокси, и завершение задачи, вызванное изнутри того же объекта, шло бы без транзакции —
 * а событие {@code import.finished} обязано попасть в outbox вместе с отметкой о завершении (§8.6).
 */
@Component
public class ImportJobStore {

    private static final Logger LOG = LoggerFactory.getLogger(ImportJobStore.class);

    /** Сколько ошибочных строк попадает в отчёт: остальные считаются, но не раздувают JSON. */
    private static final int MAX_REPORTED_ROWS = 1000;

    private final ImportJobRepository jobs;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImportJobStore(
            ImportJobRepository jobs, ApplicationEventPublisher events, ObjectMapper objectMapper, Clock clock) {
        this.jobs = jobs;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ImportJob get(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Задача импорта не найдена"));
    }

    @Transactional
    public void markStarted(UUID jobId, int total) {
        ImportJob job = get(jobId);
        job.start(total);
        jobs.save(job);
    }

    @Transactional
    public void updateProgress(UUID jobId, int imported, int rejected) {
        ImportJob job = get(jobId);
        job.progress(imported, rejected);
        jobs.save(job);
    }

    @Transactional
    public void complete(UUID jobId, int imported, int rejected, List<Map<String, Object>> report, int total) {
        if (rejected > MAX_REPORTED_ROWS) {
            LOG.warn("Отчёт импорта {} усечён: показаны первые {} ошибок из {}", jobId, MAX_REPORTED_ROWS, rejected);
        }
        ImportJob job = get(jobId);
        job.complete(imported, rejected, toJson(report), clock.instant());
        jobs.save(job);
        // FR-9.4: системы-потребители узнают, что порция согласий доехала, не опрашивая статус задания.
        events.publishEvent(CusEvent.of(
                "import_job",
                jobId.toString(),
                EventTypes.IMPORT_FINISHED,
                null,
                Map.of("imported", imported, "rejected", rejected, "total", total, "dryRun", job.isDryRun())));
    }

    @Transactional
    public void fail(UUID jobId, String message) {
        ImportJob job = get(jobId);
        job.fail(
                toJson(List.of(Map.of("line", 0, "field", "file", "message", String.valueOf(message)))),
                clock.instant());
        jobs.save(job);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
