package ru.example.inconsensu.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.integration.domain.CsvParser;
import ru.example.inconsensu.integration.domain.ImportJob;
import ru.example.inconsensu.integration.domain.ImportJobStatus;
import ru.example.inconsensu.integration.domain.ImportRow;
import ru.example.inconsensu.integration.infrastructure.ImportJobRepository;

/**
 * Импорт исторических согласий из базы клиентов (FR-4.5).
 *
 * <p>Выполняется асинхронно: файл на сто тысяч строк не должен держать HTTP-соединение. Пробный запуск
 * проходит ровно тот же путь, что и боевой, и отличается только тем, что ничего не сохраняет, — иначе
 * «сухой прогон» перестал бы что-либо гарантировать.
 *
 * <p>Каждая строка обрабатывается в своей транзакции: одна плохая строка не должна откатывать весь файл.
 */
@Service
public class ConsentImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentImportService.class);

    /** Сколько ошибочных строк попадает в отчёт: остальные считаются, но не раздувают JSON. */
    private static final int MAX_REPORTED_ROWS = 1000;

    private static final int PROGRESS_STEP = 100;

    private final ImportJobRepository jobs;
    private final ImportJobStore jobStore;
    private final ImportRowProcessor rowProcessor;
    private final ObjectMapper objectMapper;
    private final InConsensuProperties properties;
    private final ru.example.inconsensu.common.application.AfterCommitExecutor afterCommit;
    private final Clock clock;

    public ConsentImportService(
            ImportJobRepository jobs,
            ImportJobStore jobStore,
            ImportRowProcessor rowProcessor,
            ObjectMapper objectMapper,
            InConsensuProperties properties,
            ru.example.inconsensu.common.application.AfterCommitExecutor afterCommit,
            Clock clock) {
        this.jobs = jobs;
        this.jobStore = jobStore;
        this.rowProcessor = rowProcessor;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.afterCommit = afterCommit;
        this.clock = clock;
    }

    @Transactional
    public ImportJob start(String fileName, byte[] content, String source, boolean dryRun) {
        ImportJob job = jobs.save(
                new ImportJob(UUID.randomUUID(), source, fileName, dryRun, CurrentUser.login(), clock.instant()));
        String text = new String(content, StandardCharsets.UTF_8);
        if (dryRun) {
            // UI-12: боевой запуск идёт кнопкой по тому же файлу, поэтому пробная задача его хранит.
            job.keepPayload(text);
        }

        // Контекст безопасности переносится в поток задачи: иначе в аудите импорт останется без автора.
        // После коммита: запущенный внутри транзакции поток может не увидеть строку import_job,
        // и файл молча не импортируется, а задача навсегда остаётся «в очереди».
        afterCommit.execute(new DelegatingSecurityContextRunnable(() -> run(job.getId(), text)));
        return job;
    }

    /**
     * Боевой импорт по файлу успешного пробного запуска (UI-12).
     *
     * <p>Условия те же, что показывает экран: задача пробная, завершена и без отклонённых строк. Файл
     * стирается сразу после запуска — повторное нажатие кнопки не создаст второй импорт того же файла.
     */
    @Transactional
    public ImportJob runForReal(UUID dryRunJobId) {
        ImportJob dry =
                jobs.findById(dryRunJobId).orElseThrow(() -> ApiException.notFound("Задача импорта не найдена"));
        if (!dry.isDryRun() || dry.getStatus() != ImportJobStatus.COMPLETED || dry.getRejected() > 0) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "Боевой импорт доступен только после успешного пробного запуска (UI-12)");
        }
        String payload = dry.getPayload();
        if (payload == null) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "Файл пробного запуска больше не хранится — загрузите его заново");
        }
        dry.clearPayload();
        return start(dry.getFileName(), payload.getBytes(StandardCharsets.UTF_8), dry.getSource(), false);
    }

    @Transactional(readOnly = true)
    public ImportJob get(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Задача импорта не найдена"));
    }

    /** Задачи с ошибками: блок «Ошибки импорта» на дашборде (UI-2). */
    @Transactional(readOnly = true)
    public List<ImportJob> failed(int limit) {
        return jobs.findFailed(org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ImportJob> list(org.springframework.data.domain.Pageable pageable) {
        return jobs.findAllByOrderByStartedAtDesc(pageable);
    }

    /** Выполнение задачи. Открыто для теста, чтобы прогонять импорт синхронно и не ловить гонки. */
    public void run(UUID jobId, String content) {
        List<Map<String, Object>> report = new ArrayList<>();
        int imported = 0;
        int rejected = 0;
        try {
            boolean dryRun = get(jobId).isDryRun();
            List<ImportRow> rows = parse(content);
            if (!jobStore.claim(jobId, rows.size())) {
                // Задачу уже выполняет другой поток: повторный проход импортировал бы файл дважды.
                return;
            }

            // Справочники и форма читаются один раз на задачу, а не на каждую строку (NFR-1).
            ImportCache cache = new ImportCache();
            for (ImportRow row : rows) {
                if (!row.valid()) {
                    rejected++;
                    addToReport(report, row.lineNumber(), row.violations());
                    continue;
                }
                try {
                    rowProcessor.importRow(jobId, row, dryRun, cache);
                    imported++;
                } catch (ApiException e) {
                    rejected++;
                    addToReport(report, row.lineNumber(), List.of(new ImportRow.Violation("row", e.getMessage())));
                }
                if ((imported + rejected) % PROGRESS_STEP == 0) {
                    jobStore.updateProgress(jobId, imported, rejected);
                }
            }
            jobStore.complete(jobId, imported, rejected, report, rows.size());
        } catch (RuntimeException e) {
            LOG.error("Импорт {} завершился ошибкой", jobId, e);
            jobStore.fail(jobId, e.getMessage());
        }
    }

    List<ImportRow> parse(String content) {
        String trimmed = content == null ? "" : content.trim();
        List<Map<String, String>> raw =
                trimmed.startsWith("[") ? parseJson(trimmed) : CsvParser.parseWithHeader(trimmed);

        List<ImportRow> rows = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            // Номер строки файла: заголовок занимает первую, поэтому данные начинаются со второй.
            rows.add(ImportRow.from(index + 2, raw.get(index), properties.timezone()));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJson(String content) {
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(content, List.class);
            return parsed.stream()
                    .map(entry -> {
                        Map<String, String> row = new LinkedHashMap<>();
                        entry.forEach((key, value) -> row.put(
                                key.toLowerCase(java.util.Locale.ROOT), value == null ? "" : String.valueOf(value)));
                        return row;
                    })
                    .toList();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Файл импорта не является корректным JSON-массивом");
        }
    }

    private void addToReport(List<Map<String, Object>> report, int line, List<ImportRow.Violation> violations) {
        if (report.size() >= MAX_REPORTED_ROWS) {
            return;
        }
        violations.forEach(violation ->
                report.add(Map.of("line", line, "field", violation.field(), "message", violation.message())));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
