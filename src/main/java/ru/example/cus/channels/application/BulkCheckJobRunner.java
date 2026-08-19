package ru.example.cus.channels.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.channels.domain.BulkCheckJob;
import ru.example.cus.channels.infrastructure.BulkCheckJobRepository;

/**
 * Выполнение задачи массовой проверки порциями (этап 8).
 *
 * <p>Отдельный бин: {@code @Transactional} работает через прокси, а задача запускается из другого потока,
 * где транзакции вызывающего уже нет. Каждая порция считается своим вызовом синхронной проверки — правило
 * §7.6 одно и то же, и расхождений между синхронным и асинхронным ответом быть не может.
 */
@Component
public class BulkCheckJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BulkCheckJobRunner.class);
    private static final TypeReference<List<String>> IDENTIFIERS = new TypeReference<>() {};

    private final BulkCheckJobRepository jobs;
    private final ChannelService channels;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BulkCheckJobRunner(
            BulkCheckJobRepository jobs, ChannelService channels, ObjectMapper objectMapper, Clock clock) {
        this.jobs = jobs;
        this.channels = channels;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(UUID id) {
        BulkCheckJob job = jobs.findById(id).orElse(null);
        if (job == null || job.getStatus() != BulkCheckJob.Status.PENDING) {
            return;
        }
        job.start();
        jobs.save(job);

        try {
            List<String> identifiers = objectMapper.readValue(job.getIdentifiers(), IDENTIFIERS);
            List<String> allowed = new ArrayList<>();
            Map<String, String> denied = new LinkedHashMap<>();
            List<String> unknown = new ArrayList<>();

            for (int from = 0; from < identifiers.size(); from += BulkCheckJobService.CHUNK) {
                List<String> chunk =
                        identifiers.subList(from, Math.min(from + BulkCheckJobService.CHUNK, identifiers.size()));
                ChannelService.BulkResult result = channels.check(job.getChannel(), chunk, true);
                allowed.addAll(result.allowed());
                denied.putAll(result.deniedReasons());
                unknown.addAll(result.unknownIdentifiers());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("allowed", allowed);
            payload.put("deniedReasons", denied);
            payload.put("unknownIdentifiers", unknown);
            job.complete(clock.instant(), objectMapper.writeValueAsString(payload), identifiers.size(), allowed.size());
        } catch (Exception e) {
            // Без записи в журнал причина падения фоновой задачи не видна вообще нигде.
            LOG.error("Массовая проверка {} завершилась ошибкой", id, e);
            job.fail(clock.instant(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        jobs.save(job);
    }
}
