package ru.example.inconsensu.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.example.inconsensu.audit.application.AuditIntegrityService;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.audit.application.AuditVerificationService;
import ru.example.inconsensu.audit.domain.AuditEvent;
import ru.example.inconsensu.audit.domain.AuditVerification;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;

/**
 * FR-10.1, FR-10.4: подмена записи журнала обнаруживается.
 *
 * <p>Неизменяемость журнала держится на триггере и отозванных правах, но и то и другое защищает от
 * приложения, а не от того, у кого есть доступ к самой базе. Цепочка хешей нужна именно на этот случай, и
 * до сих пор ни один тест не доводил её до состояния BROKEN: проверялось только, что нетронутый журнал
 * сходится. Здесь запись правится в обход триггера — так же, как это сделал бы администратор базы, — и
 * проверка обязана это увидеть и назвать первое нарушенное событие.
 */
class AuditTamperDetectionIT extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private AuditVerificationService verifications;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void altered_event_breaks_the_chain_and_the_report_names_it() {
        String aggregateId = UUID.randomUUID().toString();
        auditService.record("tamper_aggregate", aggregateId, AuditEventType.CREATED, Map.of("step", 1));
        AuditEvent second =
                auditService.record("tamper_aggregate", aggregateId, AuditEventType.UPDATED, Map.of("step", 2));
        auditService.record("tamper_aggregate", aggregateId, AuditEventType.UPDATED, Map.of("step", 3));

        assertThat(integrityService
                        .verifyAggregate("tamper_aggregate", aggregateId)
                        .integrity())
                .as("до подмены цепочка обязана сходиться")
                .isEqualTo(AuditIntegrityService.Integrity.OK);

        String original = payloadOf(second.getId());
        try {
            tamper(second.getId(), "{\"step\": 20}");

            AuditIntegrityService.Report report = integrityService.verifyAggregate("tamper_aggregate", aggregateId);

            assertThat(report.integrity()).isEqualTo(AuditIntegrityService.Integrity.BROKEN);
            assertThat(report.problems()).isNotEmpty();
            AuditIntegrityService.Problem first = report.problems().get(0);
            assertThat(first.eventId())
                    .as("проверка обязана назвать первое нарушенное событие")
                    .isEqualTo(second.getId());
            assertThat(first.aggregateId()).isEqualTo(aggregateId);
            assertThat(first.description()).isNotBlank();
        } finally {
            // Схема общая для всех тестов: подменённую запись возвращаем на место, иначе следующая
            // полная проверка увидела бы BROKEN и упала бы в чужом тесте.
            tamper(second.getId(), original);
        }

        assertThat(integrityService
                        .verifyAggregate("tamper_aggregate", aggregateId)
                        .integrity())
                .as("после восстановления записи цепочка снова сходится")
                .isEqualTo(AuditIntegrityService.Integrity.OK);
    }

    /** UI-15: результат проверки попадает в историю — аудитор видит не только «сейчас», но и когда сломалось. */
    @Test
    void verification_history_keeps_the_broken_result() {
        String aggregateId = UUID.randomUUID().toString();
        auditService.record("tamper_aggregate", aggregateId, AuditEventType.CREATED, Map.of("step", 1));
        AuditEvent second =
                auditService.record("tamper_aggregate", aggregateId, AuditEventType.UPDATED, Map.of("step", 2));

        String original = payloadOf(second.getId());
        AuditVerification run;
        try {
            tamper(second.getId(), "{\"step\": 22}");
            run = RunAs.roles("test-auditor", List.of("AUDITOR"), () -> verifications.runNow());
        } finally {
            tamper(second.getId(), original);
        }

        assertThat(run.getIntegrity()).isEqualTo(AuditIntegrityService.Integrity.BROKEN.name());
        assertThat(run.getStatus()).isEqualTo(AuditVerification.Status.DONE);
        assertThat(run.getProblems()).contains(aggregateId);
        assertThat(run.getEventsChecked()).isPositive();

        List<AuditVerification> history =
                RunAs.roles("test-auditor", List.of("AUDITOR"), () -> verifications.history());
        assertThat(history).anySatisfy(entry -> assertThat(entry.getId()).isEqualTo(run.getId()));
    }

    private String payloadOf(Long eventId) {
        return jdbcTemplate.queryForObject("SELECT payload::text FROM audit_event WHERE id = ?", String.class, eventId);
    }

    /**
     * Правка записи в обход защиты — ровно то, от чего защищает хеш-цепочка.
     *
     * <p>Триггер снимается и возвращается на место в одной транзакции теста: без этого запись не изменить,
     * а именно её изменение и нужно смоделировать.
     */
    private void tamper(Long eventId, String payload) {
        jdbcTemplate.execute("ALTER TABLE audit_event DISABLE TRIGGER audit_event_append_only");
        try {
            jdbcTemplate.update("UPDATE audit_event SET payload = ?::jsonb WHERE id = ?", payload, eventId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE audit_event ENABLE TRIGGER audit_event_append_only");
        }
    }
}
