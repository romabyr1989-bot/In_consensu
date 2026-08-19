package ru.example.inconsensu.registry.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;

/**
 * Бизнес-метрики по согласиям (NFR-6).
 *
 * <p>Значения снимаются по расписанию, а не при каждом обращении к метрикам: {@code /actuator/prometheus}
 * опрашивается часто, и пересчёт агрегатов на каждый скрейп превратился бы в постоянную нагрузку на базу.
 */
@Component
public class ConsentMetrics implements InitializingBean {

    private static final String METRIC = "inconsensu.consents";
    private static final Duration REFRESH = Duration.ofMinutes(1);

    private final ConsentRepository consents;
    private final MeterRegistry registry;
    private final java.util.Map<ConsentStatus, AtomicLong> counters = new java.util.EnumMap<>(ConsentStatus.class);

    public ConsentMetrics(ConsentRepository consents, MeterRegistry registry) {
        this.consents = consents;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        for (ConsentStatus status : ConsentStatus.values()) {
            AtomicLong value = new AtomicLong();
            counters.put(status, value);
            Gauge.builder(METRIC, value, AtomicLong::get)
                    .description("Количество согласий по статусам")
                    .tag("status", status.name())
                    .register(registry);
        }
    }

    @Scheduled(
            fixedDelayString = "${inconsensu.metrics.refresh:PT1M}",
            initialDelayString = "${inconsensu.metrics.refresh:PT1M}")
    @Transactional(readOnly = true)
    public void refresh() {
        counters.forEach((status, value) -> value.set(consents.countByStatus(status)));
    }

    /** Для теста: пересчёт без ожидания планировщика. */
    public long valueOf(ConsentStatus status) {
        return counters.get(status).get();
    }

    static Duration refreshInterval() {
        return REFRESH;
    }
}
