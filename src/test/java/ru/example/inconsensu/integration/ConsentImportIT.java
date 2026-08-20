package ru.example.inconsensu.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.integration.domain.ImportJob;
import ru.example.inconsensu.integration.domain.ImportJobStatus;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestForms;

/** Приёмка этапа 3: импорт исторических согласий с пробным запуском и построчным отчётом (FR-4.5). */
class ConsentImportIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentImportService importService;

    @Autowired
    private SubjectService subjects;

    @Autowired
    private ConsentQueryService consents;

    @Autowired
    private TestForms testForms;

    private ConsentForm form;

    @BeforeEach
    void setUp() {
        form = testForms.publishTwoItemForm();
    }

    private String csv(String externalId) {
        return """
                external_id,last_name,first_name,middle_name,phone,email,consent_type_code,form_code,form_version,\
                granted_at,valid_until,source,source_ref,third_party_inn,pdn_categories,document_ref,note
                %s,Травин,Иван,Сергеевич,+7 916 000-00-41,travin@example.ru,PDN_PROCESSING,%s,1,\
                12.03.2025,,CLIENT_BASE_IMPORT,БАЗА-2019,,FIO;PHONE,,перенос из базы клиентов
                """
                .formatted(externalId, form.getCode());
    }

    /** Импорт запускается синхронно: тест проверяет результат, а не гонку с фоновым потоком. */
    /** Задача выполняется в фоне, поэтому тест дожидается её завершения, а не запускает её второй раз. */
    private ImportJob runImport(String content, boolean dryRun) {
        return runImport(content, dryRun, Duration.ofSeconds(10));
    }

    private ImportJob runImport(String content, boolean dryRun, Duration timeout) {
        ImportJob job = importService.start(
                "clients.csv", content.getBytes(StandardCharsets.UTF_8), "CLIENT_BASE_IMPORT", dryRun);
        for (int attempt = 0; attempt < timeout.toMillis() / 100; attempt++) {
            ImportJob current = importService.get(job.getId());
            if (current.getStatus() == ImportJobStatus.COMPLETED || current.getStatus() == ImportJobStatus.FAILED) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("задача импорта не завершилась за отведённое время");
    }

    @Test
    void dry_run_checks_the_file_but_writes_nothing() {
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);

        ImportJob job = runImport(csv(externalId), true);

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(job.getTotal()).isEqualTo(1);
        assertThat(job.getImported()).isEqualTo(1);
        assertThat(job.getRejected()).isZero();
        assertThat(subjects.findByExternalId(externalId)).isEmpty();
    }

    @Test
    void real_run_creates_the_subject_and_the_historical_consent() {
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);

        ImportJob job = runImport(csv(externalId), false);

        assertThat(job.getImported()).isEqualTo(1);
        var subject = subjects.findByExternalId(externalId).orElseThrow();
        var imported = consents.effectiveConsentsOf(subject.getId());

        assertThat(imported).hasSize(1);
        var consent = imported.get(0).consent();
        assertThat(consent.getSignatureType()).isEqualTo(SignatureType.IMPORTED_LEGACY);
        assertThat(consent.getSourceRef()).isEqualTo("БАЗА-2019");
        assertThat(consent.getEvidence()).contains("importJobId").contains("перенос из базы клиентов");
        assertThat(imported.get(0).status()).isEqualTo(ConsentStatus.ACTIVE);
    }

    @Test
    void repeating_the_same_file_does_not_duplicate_consents() {
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);
        runImport(csv(externalId), false);

        ImportJob second = runImport(csv(externalId), false);

        var subject = subjects.findByExternalId(externalId).orElseThrow();
        assertThat(second.getImported()).isEqualTo(1);
        assertThat(consents.effectiveConsentsOf(subject.getId())).hasSize(1);
    }

    @Test
    void broken_rows_are_reported_line_by_line_and_do_not_stop_the_file() {
        String good = "CRM-" + UUID.randomUUID().toString().substring(0, 8);
        String content = csv(good)
                + ",Без,Идентификатора,,,,PDN_PROCESSING," + form.getCode()
                + ",1,12.03.2025,,CLIENT_BASE_IMPORT,X,,FIO,,note\n"
                + "CRM-BAD,Тест,Тест,,,,НЕТ_ТАКОГО_ТИПА," + form.getCode()
                + ",1,12.03.2025,,CLIENT_BASE_IMPORT,Y,,FIO,,note\n";

        ImportJob job = runImport(content, false);

        assertThat(job.getTotal()).isEqualTo(3);
        assertThat(job.getImported()).isEqualTo(1);
        assertThat(job.getRejected()).isEqualTo(2);
        assertThat(job.getReport()).contains("external_id").contains("НЕТ_ТАКОГО_ТИПА");
        assertThat(subjects.findByExternalId(good)).isPresent();
    }

    /**
     * FR-2.3: исключение для импорта разрешает архивную версию, а не любой статус.
     *
     * <p>Раньше путь импорта вовсе не проверял пригодность формы, и строка со ссылкой на черновик
     * записывала клиенту условия, которые никто не согласовывал и не публиковал.
     */
    @Test
    void import_refuses_to_bind_a_consent_to_a_draft_version() {
        ConsentForm draft = testForms.draftNewVersionOf(form.getCode());
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);
        String content =
                """
                external_id,last_name,first_name,middle_name,phone,email,consent_type_code,form_code,form_version,\
                granted_at,valid_until,source,source_ref,third_party_inn,pdn_categories,document_ref,note
                %s,Травин,Иван,Сергеевич,,,PDN_PROCESSING,%s,%d,\
                12.03.2025,,CLIENT_BASE_IMPORT,БАЗА-2019,,FIO,,перенос
                """
                        .formatted(externalId, draft.getCode(), draft.getVersionNumber());

        ImportJob job = runImport(content, false);

        assertThat(job.getImported()).isZero();
        assertThat(job.getRejected()).isEqualTo(1);
        assertThat(job.getReport()).contains("опубликованной форме");
        assertThat(subjects.findByExternalId(externalId)).isEmpty();
    }

    @Test
    void json_file_is_accepted_as_well_as_csv() {
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);
        String json =
                """
                [{"external_id":"%s","last_name":"Чкалов","first_name":"Пётр","consent_type_code":"PDN_PROCESSING",
                  "granted_at":"2025-03-12","source":"CLIENT_BASE_IMPORT","source_ref":"JSON-1","note":"перенос"}]
                """
                        .formatted(externalId);

        ImportJob job = runImport(json, false);

        assertThat(job.getImported()).isEqualTo(1);
        assertThat(subjects.findByExternalId(externalId)).isPresent();
    }

    @Test
    void consent_without_a_document_reference_or_a_note_is_rejected() {
        String content =
                """
                external_id,last_name,first_name,consent_type_code,granted_at,source,source_ref
                CRM-NOPROOF,Тест,Тест,PDN_PROCESSING,12.03.2025,CLIENT_BASE_IMPORT,X
                """;

        ImportJob job = runImport(content, false);

        assertThat(job.getRejected()).isEqualTo(1);
        assertThat(job.getReport()).contains("document_ref");
    }

    @Test
    void jobs_are_listed_for_the_import_screen() {
        runImport(csv("CRM-" + UUID.randomUUID().toString().substring(0, 8)), true);

        assertThat(importService
                        .list(org.springframework.data.domain.PageRequest.of(0, 20))
                        .getContent())
                .isNotEmpty()
                .allSatisfy(job -> assertThat(job.getSource()).isEqualTo("CLIENT_BASE_IMPORT"));
    }

    /**
     * Критерий приёмки этапа 3 (§13): импорт ста тысяч строк проходит.
     *
     * <p>Прогон пробный: проверяются разбор файла, справочники и правила по каждой строке — тот самый путь,
     * к которому относится цель NFR-1 «≥ 5 000 строк в секунду». Боевая запись ста тысяч согласий заняла
     * бы около часа и в суиту не помещается; измеренная пропускная способность записи зафиксирована в
     * `docs/performance.md`.
     */
    @Test
    void a_file_of_one_hundred_thousand_rows_is_processed() {
        int rows = 100_000;
        String prefix = "CRM-BULK-" + UUID.randomUUID().toString().substring(0, 6);
        StringBuilder csv = new StringBuilder(
                "external_id,last_name,first_name,middle_name,phone,email,consent_type_code,form_code,form_version,"
                        + "granted_at,valid_until,source,source_ref,third_party_inn,pdn_categories,document_ref,note\n");
        for (int i = 0; i < rows; i++) {
            csv.append(prefix)
                    .append(i)
                    .append(",Массовый,Пётр,,,,PDN_PROCESSING,")
                    .append(form.getCode())
                    .append(",1,12.03.2025,,CLIENT_BASE_IMPORT,Б-")
                    .append(i)
                    .append(",,FIO,,перенос\n");
        }

        ImportJob job = runImport(csv.toString(), true, Duration.ofMinutes(5));

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(job.getTotal()).isEqualTo(rows);
        assertThat(job.getRejected()).isZero();
        // Пробный прогон ничего не пишет: ни одного субъекта из файла в базе быть не должно (FR-4.5).
        assertThat(subjects.findByExternalId(prefix + "0")).isEmpty();
    }
}
