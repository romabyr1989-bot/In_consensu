package ru.example.cus.registry.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.temporal.TemporalAmount;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.security.CurrentUser;
import ru.example.cus.iam.application.OperatorSettingsService;

/**
 * Архивация «холодных» данных по политике хранения (NFR-5).
 *
 * <p>Ничего не удаляется: отозванные согласия и старые события переносятся в архивные таблицы, а рабочие
 * выборки перестают их читать. Удаление коснулось бы доказательств, которые оператор обязан предъявить и
 * через годы после прекращения обработки.
 *
 * <p>По умолчанию задача выключена: сроки хранения — вопрос юридической службы (вопрос 7), и включать
 * перенос данных без её ответа нельзя.
 */
@Service
public class RetentionService {

    public static final String ENABLED = "cus.retention.enabled";
    public static final String CONSENTS_AFTER_REVOCATION = "cus.retention.consents-after-revocation";
    public static final String AUDIT_EVENTS = "cus.retention.audit-events";
    public static final String PARTNER_EXPORTS = "cus.retention.partner-exports";

    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);

    /** @param dryRun пробный прогон только считает записи и ничего не переносит */
    public record RetentionResult(
            UUID id,
            boolean dryRun,
            long consentsArchived,
            long eventsAged,
            long exportsPurged,
            long notificationsPurged) {}

    private final JdbcTemplate jdbc;
    private final OperatorSettingsService settings;
    private final Clock clock;

    public RetentionService(JdbcTemplate jdbc, OperatorSettingsService settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "${cus.jobs.retention.cron:0 40 2 * * *}", zone = "${cus.timezone:Europe/Moscow}")
    @SchedulerLock(name = "retention", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
    public void scheduled() {
        if (!Boolean.parseBoolean(settings.value(ENABLED))) {
            LOG.debug("Ретенция выключена настройкой {}", ENABLED);
            return;
        }
        RetentionResult result = run(false);
        LOG.info(
                "Ретенция: архивировано согласий {}, очищено выгрузок {}, удалено уведомлений {}; "
                        + "событий журнала за сроком хранения {} — требуется ручная архивация",
                result.consentsArchived(),
                result.exportsPurged(),
                result.notificationsPurged(),
                result.eventsAged());
    }

    /**
     * Прогон политики хранения.
     *
     * <p>Перенос выполняется одним оператором {@code INSERT … SELECT} и {@code DELETE}: построчная обработка
     * миллионов записей заняла бы часы, а результат тот же.
     */
    @Transactional
    public RetentionResult run(boolean dryRun) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant consentsBefore = horizon(now, CONSENTS_AFTER_REVOCATION, Period.ofYears(3));
        Instant eventsBefore = horizon(now, AUDIT_EVENTS, Period.ofYears(5));
        Instant exportsBefore = horizon(now, PARTNER_EXPORTS, Period.ofDays(30));

        long consents =
                count("select count(*) from consent where revoked_at is not null and revoked_at < ?", consentsBefore);
        long agedEvents = count("select count(*) from audit_event where occurred_at < ?", eventsBefore);
        long exports = count(
                "select count(*) from partner_export_log where expires_at < ? and content is not null", exportsBefore);
        long notifications =
                count("select count(*) from notification where status = 'SENT' and created_at < ?", exportsBefore);

        if (!dryRun) {
            archiveConsents(consentsBefore);
            // Тело выгрузки после истечения ссылки хранить незачем: сам факт выгрузки остаётся в журнале.
            jdbc.update(
                    "update partner_export_log set content = null where expires_at < ? and content is not null",
                    java.sql.Timestamp.from(exportsBefore));
            jdbc.update(
                    "delete from notification where status = 'SENT' and created_at < ?",
                    java.sql.Timestamp.from(exportsBefore));
        }

        jdbc.update(
                """
                insert into retention_run (id, started_at, finished_at, dry_run, consents_archived,
                                           events_aged, exports_purged, notifications_purged, started_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(clock.instant()),
                dryRun,
                dryRun ? 0 : consents,
                agedEvents,
                dryRun ? 0 : exports,
                dryRun ? 0 : notifications,
                CurrentUser.login());

        return new RetentionResult(id, dryRun, consents, agedEvents, exports, notifications);
    }

    /**
     * Перенос отозванных согласий в архивную таблицу.
     *
     * <p>Ссылки на согласие из уведомлений гасятся заранее: внешний ключ иначе не даст перенести строку, а
     * уведомление о давно отозванном согласии само по себе ничего не доказывает.
     */
    private void archiveConsents(Instant before) {
        java.sql.Timestamp threshold = java.sql.Timestamp.from(before);
        jdbc.update(
                """
                insert into consent_archive
                select c.*, now() from consent c where c.revoked_at is not null and c.revoked_at < ?
                """,
                threshold);
        jdbc.update(
                """
                update notification set consent_id = null
                where consent_id in (select id from consent where revoked_at is not null and revoked_at < ?)
                """,
                threshold);
        // Замещённое согласие ссылается на своего преемника: если преемник уезжает в архив, ссылка
        // должна погаснуть, иначе внешний ключ откатит весь прогон ретенции.
        jdbc.update(
                """
                update consent set superseded_by_id = null
                where superseded_by_id in (select id from consent where revoked_at is not null and revoked_at < ?)
                """,
                threshold);
        jdbc.update("delete from consent where revoked_at is not null and revoked_at < ?", threshold);
    }

    private long count(String sql, Instant before) {
        Long value = jdbc.queryForObject(sql, Long.class, java.sql.Timestamp.from(before));
        return value == null ? 0 : value;
    }

    /** Календарные сроки считаются в таймзоне оператора: «три года» — это дата, а не 1095 суток (§8.7). */
    private Instant horizon(Instant now, String key, TemporalAmount fallback) {
        return now.atZone(clock.getZone()).minus(amount(key, fallback)).toInstant();
    }

    /** Сроки задаются в ISO-8601: «P3Y» — три года, «P30D» — тридцать дней. */
    private TemporalAmount amount(String key, TemporalAmount fallback) {
        String value = settings.value(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return value.contains("T") ? Duration.parse(value) : Period.parse(value);
        } catch (RuntimeException e) {
            LOG.warn("Настройка {} задана неверно: {}", key, value);
            return fallback;
        }
    }
}
