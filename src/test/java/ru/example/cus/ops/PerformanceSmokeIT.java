package ru.example.cus.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.example.cus.channels.application.ChannelService;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.registry.application.SubjectCardService;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;

/**
 * Нагрузочный smoke (§13, этап 8; цели NFR-1).
 *
 * <p>Проверяются те операции, у которых NFR-1 задаёт предел: каналы одного субъекта, карточка и массовая
 * проверка. Объём синтетический и заведомо меньше проектного (5 млн субъектов): цель прогона — поймать
 * отсутствие индекса и запрос вида N+1, а не подтвердить цифры на боевом объёме. Пороги теста поэтому
 * мягче целевых, а измеренные значения выгружаются в отчёт {@code target/performance-report.md}.
 */
class PerformanceSmokeIT extends AbstractIntegrationTest {

    private static final int SUBJECTS = 2_000;
    private static final int WARMUP = 20;
    private static final int MEASUREMENTS = 200;

    /** Порог теста: на порядок мягче цели NFR-1, чтобы прогон не зависел от загрузки машины сборки. */
    private static final Duration CHANNELS_LIMIT = Duration.ofMillis(500);

    private static final Duration CARD_LIMIT = Duration.ofMillis(1_000);
    private static final Duration BULK_LIMIT = Duration.ofSeconds(20);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChannelService channels;

    @Autowired
    private SubjectCardService cards;

    @Test
    void key_operations_stay_within_the_smoke_limits() throws Exception {
        List<UUID> subjectIds = seed();
        Map<String, String> report = new LinkedHashMap<>();
        report.put("Объём", SUBJECTS + " субъектов, по 2 согласия на каждого");

        Duration channelsP95 =
                measure(MEASUREMENTS, index -> () -> channels.channelsOf(subjectIds.get(index % subjectIds.size())));
        report.put("Каналы одного субъекта, p95", format(channelsP95) + " (цель NFR-1: 50 мс)");

        Duration cardP95 = measure(100, index -> () -> cards.cardOf(subjectIds.get(index % subjectIds.size())));
        report.put("Карточка клиента, p95", format(cardP95) + " (цель NFR-1: 200 мс)");

        List<String> bulk = subjectIds.stream().map(UUID::toString).toList();
        Instant start = Instant.now();
        var bulkResult = RunAs.roles(
                "test-marketing", List.of("MARKETING"), () -> channels.check(CommunicationChannel.EMAIL, bulk, false));
        Duration bulkTime = Duration.between(start, Instant.now());
        report.put(
                "Массовая проверка " + bulk.size() + " идентификаторов",
                format(bulkTime) + " (цель NFR-1: 2 с на 10 000)");

        report.put("Планы выполнения", explain(subjectIds.get(0)));
        writeReport(report);

        assertThat(bulkResult.allowed().size() + bulkResult.deniedReasons().size())
                .as("ответ должен покрывать все идентификаторы")
                .isEqualTo(bulk.size());
        assertThat(channelsP95).isLessThan(CHANNELS_LIMIT);
        assertThat(cardP95).isLessThan(CARD_LIMIT);
        assertThat(bulkTime).isLessThan(BULK_LIMIT);
    }

    /** Синтетические данные пишутся пакетно через JDBC: прогон через сервисы занял бы минуты (§14.6 — данные вымышленные). */
    private List<UUID> seed() {
        UUID typeId = jdbc.queryForObject("select id from consent_type where code = 'PDN_PROCESSING'", UUID.class);
        UUID adTypeId = jdbc.queryForObject("select id from consent_type where code = 'ADVERTISING_EMAIL'", UUID.class);

        List<UUID> ids = new ArrayList<>(SUBJECTS);
        List<Object[]> subjects = new ArrayList<>();
        List<Object[]> consents = new ArrayList<>();
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        for (int i = 0; i < SUBJECTS; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            subjects.add(new Object[] {
                id, "PERF-" + suffix + "-" + i, "Тестов", "Тест", "Тестович", java.sql.Timestamp.from(now)
            });
            consents.add(consentRow(id, typeId, now, "perf-" + suffix + "-" + i + "-base"));
            consents.add(consentRow(id, adTypeId, now, "perf-" + suffix + "-" + i + "-ad"));
        }

        jdbc.batchUpdate(
                """
                insert into subject (id, external_id, last_name, first_name, middle_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                subjects.stream()
                        .map(row -> new Object[] {row[0], row[1], row[2], row[3], row[4], row[5], row[5]})
                        .toList());
        jdbc.batchUpdate(
                """
                insert into consent (id, subject_id, consent_type_id, source, status, granted_at,
                                     pdn_categories, purposes, signature_type, evidence, idempotency_key,
                                     created_at, updated_at)
                values (?, ?, ?, 'CONTRACT', 'ACTIVE', ?, ?, ?, 'SIMPLE_ES_SMS', '{}'::jsonb, ?, ?, ?)
                """,
                consents);
        return ids;
    }

    private Object[] consentRow(UUID subjectId, UUID typeId, Instant now, String key) {
        java.sql.Timestamp moment = java.sql.Timestamp.from(now);
        return new Object[] {
            UUID.randomUUID(),
            subjectId,
            typeId,
            moment,
            new String[] {"FIO", "EMAIL"},
            new String[] {"тестирование производительности"},
            key,
            moment,
            moment
        };
    }

    private Duration measure(int iterations, java.util.function.IntFunction<Supplier<?>> call) {
        for (int i = 0; i < WARMUP; i++) {
            RunAs.roles("test-manager", List.of("MANAGER"), call.apply(i)::get);
        }
        List<Long> samples = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            RunAs.roles("test-manager", List.of("MANAGER"), call.apply(i)::get);
            samples.add(System.nanoTime() - started);
        }
        samples.sort(Long::compareTo);
        return Duration.ofNanos(samples.get((int) Math.floor(samples.size() * 0.95) - 1));
    }

    /** Планы ключевых запросов идут в отчёт: отсутствие индекса видно именно здесь (NFR-1). */
    private String explain(UUID subjectId) {
        List<String> plans = new ArrayList<>();
        plans.add(plan(
                "согласия субъекта",
                "explain select * from consent where subject_id = ? and superseded_by_id is null",
                subjectId));
        plans.add(plan(
                "поиск по контакту",
                "explain select * from subject_contact where type = 'PHONE' and value_normalized = '+79160000041'"));
        plans.add(plan(
                "истекающие согласия",
                "explain select * from consent where valid_until between now() and now() + interval '30 days'"));
        return String.join("\n", plans);
    }

    private String plan(String title, String sql, Object... args) {
        List<String> rows = jdbc.query(sql, (rs, index) -> rs.getString(1), args);
        return "\n**" + title + "**\n\n```\n" + String.join("\n", rows) + "\n```";
    }

    private static String format(Duration duration) {
        return duration.toMillis() + " мс";
    }

    private void writeReport(Map<String, String> report) throws Exception {
        StringBuilder text = new StringBuilder("# Отчёт нагрузочного smoke\n\n");
        text.append("Сформирован тестом `PerformanceSmokeIT` при сборке.\n\n");
        report.forEach((key, value) ->
                text.append("- **").append(key).append("**: ").append(value).append('\n'));
        Path target = Path.of("target", "performance-report.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, text.toString());
    }
}
