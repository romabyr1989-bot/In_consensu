package ru.example.inconsensu.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.integration.domain.ImportJob;
import ru.example.inconsensu.integration.domain.ImportJobStatus;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestForms;

/**
 * Пропускная способность боевой записи импорта (NFR-1, вопрос 20 в OPEN_QUESTIONS).
 *
 * <p>Измеряется именно запись: разбор и проверка уже укладываются в цель и покрыты приёмочным тестом на
 * сто тысяч строк. Размер выборки небольшой — тест сторожит регресс и даёт число для
 * `docs/performance.md`, а не заменяет нагрузочное испытание на проектных объёмах.
 */
class ImportWriteThroughputIT extends AbstractIntegrationTest {

    /** Строк в замере: столько успевает пройти в обычной сборке, оставаясь показательным. */
    private static final int ROWS = 2_000;

    /**
     * Нижняя граница замера.
     *
     * <p>Измерено около 108 строк/с (см. `docs/performance.md`); порог намеренно ниже, чтобы тест ловил
     * деградацию вдвое, а не колебания загруженной машины. Цель NFR-1 в 5 000 строк/с этим путём
     * недостижима — почему, разобрано там же и в вопросе 20.
     */
    private static final double MIN_ROWS_PER_SECOND = 55;

    @Autowired
    private ConsentImportService imports;

    @Autowired
    private SubjectService subjects;

    @Autowired
    private TestForms testForms;

    private ConsentForm form;

    @BeforeEach
    void publishForm() {
        form = testForms.publishTwoItemForm();
    }

    @Test
    void write_path_keeps_its_measured_throughput() throws Exception {
        String prefix = "CRM-WRITE-" + UUID.randomUUID().toString().substring(0, 6);
        String csv = csv(prefix, ROWS);

        Instant started = Instant.now();
        ImportJob job = RunAs.roles("test-admin", List.of("ADMIN"), () -> run(csv));
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(job.getImported()).isEqualTo(ROWS);
        assertThat(job.getRejected()).isZero();
        assertThat(subjects.findByExternalId(prefix + "0")).isPresent();

        double rowsPerSecond = ROWS / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        report(rowsPerSecond, elapsed);
        assertThat(rowsPerSecond)
                .as("запись импорта: %.0f строк/с за %s", rowsPerSecond, elapsed)
                .isGreaterThan(MIN_ROWS_PER_SECOND);
    }

    private ImportJob run(String csv) {
        ImportJob started = imports.start(
                "throughput.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "CLIENT_BASE_IMPORT", false);
        Duration timeout = Duration.ofMinutes(5);
        for (int attempt = 0; attempt < timeout.toMillis() / 200; attempt++) {
            ImportJob current = imports.get(started.getId());
            if (current.getStatus() == ImportJobStatus.COMPLETED || current.getStatus() == ImportJobStatus.FAILED) {
                return current;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("импорт не завершился за " + timeout);
    }

    /** Число попадает в отчёт сборки рядом с остальными замерами NFR-1. */
    private void report(double rowsPerSecond, Duration elapsed) throws Exception {
        Path report = Path.of("target", "import-write-throughput.md");
        Files.writeString(
                report,
                "# Запись импорта%n%n| Строк | Время | Строк/с |%n|---|---|---|%n| %d | %s | %.0f |%n"
                        .formatted(ROWS, elapsed, rowsPerSecond));
    }

    private String csv(String prefix, int rows) {
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
        return csv.toString();
    }
}
