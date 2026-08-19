package ru.example.inconsensu.channels.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.channels.domain.BulkCheckJob;
import ru.example.inconsensu.channels.infrastructure.BulkCheckJobRepository;
import ru.example.inconsensu.common.application.AfterCommitExecutor;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.security.CurrentUser;

/**
 * Асинхронная массовая проверка канала (этап 8, FR-6.4).
 *
 * <p>Синхронный вызов ограничен десятью тысячами идентификаторов: держать HTTP-соединение на время
 * проверки всей базы бессмысленно. Задача считается порциями в фоне, клиент забирает результат по
 * идентификатору.
 */
@Service
public class BulkCheckJobService {

    /** Размер порции: столько же, сколько выдерживает синхронный вызов (FR-6.4). */
    public static final int CHUNK = 10_000;

    private static final int MAX_IDENTIFIERS = 1_000_000;
    private static final Logger LOG = LoggerFactory.getLogger(BulkCheckJobService.class);

    private final BulkCheckJobRepository jobs;
    private final BulkCheckJobRunner runner;
    private final ObjectMapper objectMapper;
    private final AfterCommitExecutor afterCommit;
    private final Clock clock;

    public BulkCheckJobService(
            BulkCheckJobRepository jobs,
            BulkCheckJobRunner runner,
            ObjectMapper objectMapper,
            AfterCommitExecutor afterCommit,
            Clock clock) {
        this.jobs = jobs;
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.afterCommit = afterCommit;
        this.clock = clock;
    }

    @Transactional
    public BulkCheckJob submit(CommunicationChannel channel, List<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Список идентификаторов пуст");
        }
        if (identifiers.size() > MAX_IDENTIFIERS) {
            throw new ApiException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "За одну задачу проверяется не более " + MAX_IDENTIFIERS + " записей");
        }
        BulkCheckJob job = jobs.save(new BulkCheckJob(
                UUID.randomUUID(),
                channel,
                toJson(identifiers),
                identifiers.size(),
                CurrentUser.login(),
                clock.instant()));
        UUID id = job.getId();
        afterCommit.execute(() -> {
            try {
                runner.run(id);
            } catch (RuntimeException e) {
                LOG.error("Массовая проверка {} завершилась ошибкой", id, e);
            }
        });
        return job;
    }

    @Transactional(readOnly = true)
    public BulkCheckJob get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Задача проверки не найдена"));
    }

    /**
     * Синхронный прогон для тестов и демонстрации: тот же расчёт, что и в фоне.
     *
     * <p>Фоновый запуск после коммита тоже сработает, но повторного счёта не будет: обработчик берёт только
     * задачи в статусе «в очереди».
     */
    public BulkCheckJob runNow(CommunicationChannel channel, List<String> identifiers) {
        BulkCheckJob job = submit(channel, identifiers);
        runner.run(job.getId());
        return get(job.getId());
    }

    /**
     * Результат задачи в виде CSV (FR-6.4, этап 8).
     *
     * <p>Колонки: идентификатор, решение, причина запрета. Причина пустая у разрешённых — так файл читается
     * и человеком, и системой рассылки без дополнительных правил.
     */
    @Transactional(readOnly = true)
    public String toCsv(UUID jobId) {
        BulkCheckJob job = get(jobId);
        if (job.getStatus() != BulkCheckJob.Status.DONE || job.getResult() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Задача ещё не завершена: файл будет доступен после расчёта");
        }
        try {
            var result = objectMapper.readTree(job.getResult());
            StringBuilder csv = new StringBuilder("identifier,allowed,reason\n");
            result.path("allowed").forEach(node -> csv.append(node.asText()).append(",true,\n"));
            result.path("deniedReasons").fields().forEachRemaining(entry -> csv.append(entry.getKey())
                    .append(",false,")
                    .append(entry.getValue().asText())
                    .append('\n'));
            result.path("unknownIdentifiers")
                    .forEach(node -> csv.append(node.asText()).append(",false,UNKNOWN_IDENTIFIER\n"));
            return csv.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось собрать файл результата", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать список идентификаторов", e);
        }
    }
}
