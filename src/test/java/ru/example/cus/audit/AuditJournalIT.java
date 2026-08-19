package ru.example.cus.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.example.cus.audit.application.AuditAnchorJob;
import ru.example.cus.audit.application.AuditIntegrityService;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.audit.domain.AuditEvent;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.support.AbstractIntegrationTest;

/** Приёмка этапа 1: журналы неизменяемы (FR-10.2) и связаны хеш-цепочкой (FR-10.1). */
class AuditJournalIT extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private AuditAnchorJob anchorJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void events_of_one_aggregate_form_a_verifiable_chain() {
        String aggregateId = UUID.randomUUID().toString();

        AuditEvent first =
                auditService.record("test_aggregate", aggregateId, AuditEventType.CREATED, Map.of("step", 1));
        AuditEvent second =
                auditService.record("test_aggregate", aggregateId, AuditEventType.UPDATED, Map.of("step", 2));

        assertThat(first.getPrevHash()).isNull();
        assertThat(second.getPrevHash()).isEqualTo(first.getHash());
        assertThat(integrityService
                        .verifyAggregate("test_aggregate", aggregateId)
                        .integrity())
                .isEqualTo(AuditIntegrityService.Integrity.OK);
    }

    @Test
    void full_verification_reports_ok_on_an_untouched_journal() {
        auditService.record("test_aggregate", UUID.randomUUID().toString(), AuditEventType.CREATED, Map.of());

        AuditIntegrityService.Report report = integrityService.verifyAll();

        assertThat(report.integrity()).isEqualTo(AuditIntegrityService.Integrity.OK);
        assertThat(report.problems()).isEmpty();
        assertThat(report.eventsChecked()).isPositive();
    }

    @Test
    void audit_events_cannot_be_updated_or_deleted() {
        auditService.record("test_aggregate", UUID.randomUUID().toString(), AuditEventType.CREATED, Map.of());

        assertThatThrownBy(() -> jdbcTemplate.update("update audit_event set event_type = 'UPDATED'"))
                .hasMessageContaining("только для добавления");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from audit_event"))
                .hasMessageContaining("только для добавления");
    }

    @Test
    void personal_data_access_log_cannot_be_updated_or_deleted() {
        jdbcTemplate.update(
                "insert into pdn_access_log (endpoint, subjects_count, occurred_at) values (?, ?, now())",
                "/api/v1/subjects",
                1);

        assertThatThrownBy(() -> jdbcTemplate.update("update pdn_access_log set endpoint = 'x'"))
                .hasMessageContaining("только для добавления");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from pdn_access_log"))
                .hasMessageContaining("только для добавления");
    }

    @Test
    void daily_anchor_is_written_once_and_survives_verification() {
        // Anchored day is in the past on purpose: an anchor of the current day would go stale as soon as any other
        // test appends an event, and verifyAll() would then report a false BROKEN.
        LocalDate yesterday = LocalDate.now().minusDays(1);

        anchorJob.createAnchor(yesterday);
        anchorJob.createAnchor(yesterday);

        Integer anchors = jdbcTemplate.queryForObject(
                "select count(*) from audit_anchor where day = ?", Integer.class, yesterday);
        assertThat(anchors).isEqualTo(1);

        AuditIntegrityService.Report report = integrityService.verifyAll();
        assertThat(report.anchorsChecked()).isPositive();
        assertThat(report.integrity()).isEqualTo(AuditIntegrityService.Integrity.OK);
    }
}
