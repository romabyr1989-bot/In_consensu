package ru.example.inconsensu.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;

/**
 * Ограничение частоты обращений самообслуживания (FR-8.1).
 *
 * <p>Счётчик в памяти экземпляра: лимит защищает от перебора и от случайного цикла в личном кабинете, а не
 * от распределённой атаки. При нескольких экземплярах фактический предел умножается на их число — для
 * кластерного лимита нужен общий счётчик (Redis или таблица), см. открытый вопрос 17.
 */
@Component
public class SelfServiceRateLimiter {

    private static final int MAX_REQUESTS = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public SelfServiceRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key) {
        Instant now = clock.instant();
        Deque<Instant> window = hits.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());

        while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
            window.pollFirst();
        }
        if (window.size() >= MAX_REQUESTS) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Слишком много обращений. Повторите через минуту.");
        }
        window.addLast(now);
    }
}
