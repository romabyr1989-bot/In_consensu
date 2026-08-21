package ru.example.inconsensu.iam.application;

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
 * Ограничение частоты неудачных входов с одного адреса (FR-11.1).
 *
 * <p>Блокировка учётной записи после N неудач защищает конкретного сотрудника, но не мешает перебирать
 * логины: злоумышленник пробует по одному паролю на сотню имён и не упирается ни во что. ТЗ требует и
 * блокировку, и rate limit — здесь вторая половина.
 *
 * <p>Считаются только неудачи: удачный вход счётчик не наращивает, поэтому обычная работа и интеграции с
 * общего адреса под лимит не попадают.
 *
 * <p>Счётчик живёт в памяти экземпляра: при нескольких экземплярах фактический предел умножается на их
 * число, для кластерного лимита нужен общий счётчик (вопрос 17), как и у самообслуживания.
 */
@Component
public class LoginRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Предел неудач в минуту с одного адреса; настройка, потому что у контуров разная топология сети. */
    private final int maxFailures;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginRateLimiter(Clock clock, ru.example.inconsensu.common.config.InConsensuProperties properties) {
        this(clock, properties.security().login().maxFailuresPerMinute());
    }

    /** Прямой предел: нужен тестам, чтобы не заводить целые настройки ради одного числа. */
    public LoginRateLimiter(Clock clock, int maxFailures) {
        this.clock = clock;
        this.maxFailures = maxFailures;
    }

    /** Бросает 429, если с этого адреса за последнюю минуту уже было слишком много неудач. */
    public void check(String source) {
        if (recent(source).size() >= maxFailures) {
            throw new ApiException(
                    ErrorCode.TOO_MANY_REQUESTS, "Слишком много неудачных попыток входа. Повторите через минуту.");
        }
    }

    /** Отмечает неудачу: только она приближает адрес к пределу. */
    public void registerFailure(String source) {
        recent(source).addLast(clock.instant());
    }

    private Deque<Instant> recent(String source) {
        Instant edge = clock.instant().minus(WINDOW);
        Deque<Instant> window =
                failures.computeIfAbsent(source == null ? "" : source, ignored -> new ConcurrentLinkedDeque<>());
        while (!window.isEmpty() && window.peekFirst().isBefore(edge)) {
            window.pollFirst();
        }
        return window;
    }
}
