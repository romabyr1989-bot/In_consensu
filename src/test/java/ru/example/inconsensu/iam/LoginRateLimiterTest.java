package ru.example.inconsensu.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.iam.application.LoginRateLimiter;

/** FR-11.1: перебор логинов с одного адреса упирается в ограничение частоты, а не только в блокировку. */
class LoginRateLimiterTest {

    /** Часы, которые двигает сам тест: ждать минуту в сборке недопустимо. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-21T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    @Test
    void failures_from_one_source_run_into_the_limit() {
        MovableClock clock = new MovableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 20);

        for (int attempt = 0; attempt < 20; attempt++) {
            limiter.check("10.0.0.1");
            limiter.registerFailure("10.0.0.1");
        }

        assertThatThrownBy(() -> limiter.check("10.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void another_source_is_not_affected() {
        MovableClock clock = new MovableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 20);

        for (int attempt = 0; attempt < 25; attempt++) {
            limiter.registerFailure("10.0.0.1");
        }

        limiter.check("10.0.0.2");
    }

    /** Успешный вход счётчик не наращивает: иначе обычная работа с общего адреса упиралась бы в лимит. */
    @Test
    void successful_logins_do_not_count() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MovableClock(), 20);

        for (int attempt = 0; attempt < 100; attempt++) {
            limiter.check("10.0.0.1");
        }
    }

    @Test
    void the_window_slides_and_the_source_is_let_back_in() {
        MovableClock clock = new MovableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 20);
        for (int attempt = 0; attempt < 20; attempt++) {
            limiter.registerFailure("10.0.0.1");
        }

        clock.advance(Duration.ofMinutes(2));

        limiter.check("10.0.0.1");
        assertThat(clock.instant()).isAfter(Instant.parse("2026-08-21T10:00:00Z"));
    }
}
