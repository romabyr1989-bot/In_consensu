package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.integration.domain.ImportJob;
import ru.example.inconsensu.integration.domain.ImportJobStatus;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * Приёмка UI-12 на слое рабочего места: загрузка базы клиентов, пробный прогон и боевой импорт.
 *
 * <p>Боевой импорт правит реестр целиком и назад не отыгрывается, поэтому проверяется решение сервера,
 * а не экран: каким считается режим без явного указания, что после прогона появилось в реестре и кому
 * операция доступна. Прежние проверки ходили по страницам Thymeleaf, где режим задавался чекбоксом;
 * одностраничное приложение присылает признак параметром, и умолчание должно остаться безопасным.
 */
@AutoConfigureMockMvc
class WorkplaceImportApiIT extends AbstractIntegrationTest {

    private static final String IMPORT = "/ui/api/import";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentImportService imports;

    @Autowired
    private SubjectService subjects;

    private ConsentForm form;

    @BeforeEach
    void publishForm() {
        form = testForms.publishTwoItemForm();
    }

    @Test
    void upload_without_the_flag_stays_a_dry_run_and_writes_nothing() throws Exception {
        String externalId = newExternalId();
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        String started = mockMvc.perform(upload(csv(externalId)).session(dpo).with(csrf()))
                .andExpect(status().isOk())
                // FR-4.5: умолчание безопасное — запрос без признака режима запускает пробный прогон.
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.source").value("CLIENT_BASE_IMPORT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ImportJob job = awaitFinished(jobIdOf(started));
        assertThat(job.isDryRun()).isTrue();
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        // Строка проверку прошла — и всё-таки в реестре её нет: этим пробный прогон и отличается.
        assertThat(job.getImported()).isEqualTo(1);
        assertThat(job.getRejected()).isZero();
        assertThat(subjects.findByExternalId(externalId))
                .as("пробный прогон не пишет в реестр")
                .isEmpty();
    }

    @Test
    void explicit_real_run_puts_the_rows_into_the_registry() throws Exception {
        String externalId = newExternalId();
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        String started = mockMvc.perform(
                        upload(csv(externalId)).session(dpo).with(csrf()).param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        ImportJob job = awaitFinished(jobIdOf(started));
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(job.getImported()).isEqualTo(1);
        assertThat(job.getRejected()).isZero();
        assertThat(subjects.findByExternalId(externalId))
                .as("боевой импорт заводит клиента по внешнему идентификатору")
                .isPresent();
    }

    @Test
    void real_run_of_a_checked_file_imports_it_once_and_not_twice() throws Exception {
        String externalId = newExternalId();
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        String checked = mockMvc.perform(upload(csv(externalId)).session(dpo).with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID dryRunId = jobIdOf(checked);
        assertThat(awaitFinished(dryRunId).getRejected()).isZero();

        // UI-12: боевой импорт идёт по файлу успешного пробного прогона, без повторной загрузки.
        String real = mockMvc.perform(
                        post(IMPORT + "/" + dryRunId + "/run").session(dpo).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID realId = jobIdOf(real);
        assertThat(realId).isNotEqualTo(dryRunId);
        assertThat(awaitFinished(realId).getImported()).isEqualTo(1);
        assertThat(subjects.findByExternalId(externalId)).isPresent();

        // Файл после запуска не хранится: повторное нажатие не должно импортировать его второй раз.
        long jobsBefore = importJobs();
        mockMvc.perform(post(IMPORT + "/" + dryRunId + "/run").session(dpo).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:conflict"));
        assertThat(importJobs())
                .as("повторный запуск не создаёт второй импорт того же файла")
                .isEqualTo(jobsBefore);
        assertThat(imports.get(dryRunId).getPayload()).isNull();
    }

    @Test
    void row_errors_are_downloaded_as_a_csv_report() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String broken =
                """
                external_id,last_name,first_name,consent_type_code,granted_at,source,note
                ,Травин,Иван,PDN_PROCESSING,12.03.2025,CLIENT_BASE_IMPORT,перенос
                """;

        String started = mockMvc.perform(upload(broken).session(dpo).with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID jobId = jobIdOf(started);
        ImportJob job = awaitFinished(jobId);
        assertThat(job.getRejected()).isPositive();

        mockMvc.perform(get(IMPORT + "/" + jobId).session(dpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.rejected").value(job.getRejected()))
                .andExpect(jsonPath("$.report.length()").value(greaterThan(0)));

        MockHttpServletResponse report = mockMvc.perform(
                        get(IMPORT + "/" + jobId + "/report.csv").session(dpo))
                .andExpect(status().isOk())
                // UI-0.10: имя файла собрано из номера задачи — ПДн из выгрузки в него не попадают.
                .andExpect(header().string(
                                "Content-Disposition", "attachment; filename=\"import-report-" + jobId + ".csv\""))
                .andReturn()
                .getResponse();

        // Разделитель — точка с запятой: файл открывается Excel в русской локали, а не одной колонкой.
        assertThat(report.getContentAsString(StandardCharsets.UTF_8))
                .startsWith("Строка;Поле;Причина")
                .contains("external_id");
    }

    @Test
    void import_is_closed_for_every_role_except_the_dpo_and_the_administrator() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            boolean allowed = role == RoleCode.DPO || role == RoleCode.ADMIN;
            MockHttpSession session = loginAs(role.name());

            MockHttpServletResponse jobs =
                    mockMvc.perform(get(IMPORT).session(session)).andReturn().getResponse();
            assertThat(jobs.getStatus())
                    .as("список задач импорта для роли %s", role)
                    .isEqualTo(allowed ? 200 : 403);
            if (allowed) {
                continue;
            }
            assertDenied(jobs, "список задач импорта для роли " + role);

            // Спрятать раздел в меню недостаточно: загрузку отклоняет сервер, и реестр остаётся прежним.
            String externalId = newExternalId();
            MockHttpServletResponse uploaded = mockMvc.perform(upload(csv(externalId))
                            .session(session)
                            .with(csrf())
                            .param("dryRun", "false"))
                    .andReturn()
                    .getResponse();
            assertThat(uploaded.getStatus()).as("загрузка файла ролью %s", role).isEqualTo(403);
            assertDenied(uploaded, "загрузка файла ролью " + role);
            assertThat(subjects.findByExternalId(externalId))
                    .as("отказ роли %s не должен ничего импортировать", role)
                    .isEmpty();
        }
    }

    @Test
    void upload_without_a_csrf_token_imports_nothing() throws Exception {
        String externalId = newExternalId();
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        // Без токена чужая страница могла бы загрузить свой файл в реестр за сотрудника. Ответ — код,
        // а не переход на страницу входа: приложению нужно отличать это от успеха.
        mockMvc.perform(upload(csv(externalId)).session(dpo).param("dryRun", "false"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"));

        assertThat(subjects.findByExternalId(externalId)).isEmpty();
    }

    /** Отказ приходит телом ProblemDetail: приложение ждёт JSON, а не кусок вёрстки (UI-0.9). */
    private static void assertDenied(MockHttpServletResponse response, String where) throws Exception {
        String contentType = response.getContentType() == null ? "" : response.getContentType();
        assertThat(contentType).as("тип содержимого отказа: %s", where).startsWith("application/problem+json");

        JsonNode problem = MAPPER.readTree(response.getContentAsString());
        assertThat(problem.path("type").asText()).as("код ошибки: %s", where).startsWith("urn:inconsensu:error:");
        assertThat(problem.path("status").asInt())
                .as("код состояния: %s", where)
                .isEqualTo(403);
        // Причина не уточняется: рассказывать, чего не хватает, значит рассказывать о правах чужой роли.
        assertThat(problem.path("title").asText())
                .as("заголовок отказа: %s", where)
                .isNotBlank();
    }

    /** Файл уходит multipart-частью: в адресе строки базы клиентов оказаться не должны (UI-0.10). */
    private MockHttpServletRequestBuilder upload(String content) {
        MockMultipartFile file =
                new MockMultipartFile("file", "clients.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        return multipart(IMPORT).file(file).param("source", "CLIENT_BASE_IMPORT");
    }

    /** Номер задачи берётся из тела ответа: приложение узнаёт его именно так. */
    private static UUID jobIdOf(String json) throws Exception {
        return UUID.fromString(MAPPER.readTree(json).get("id").asText());
    }

    private long importJobs() {
        return imports.list(PageRequest.of(0, 1)).getTotalElements();
    }

    /** Импорт идёт в фоне: приложение опрашивает прогресс, тест — состояние задачи. */
    private ImportJob awaitFinished(UUID jobId) {
        Duration timeout = Duration.ofSeconds(30);
        for (int attempt = 0; attempt < timeout.toMillis() / 100; attempt++) {
            ImportJob current = imports.get(jobId);
            if (current.getStatus() == ImportJobStatus.COMPLETED || current.getStatus() == ImportJobStatus.FAILED) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("задача импорта не завершилась за " + timeout);
    }

    private static String newExternalId() {
        return "CRM-IMP-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String csv(String externalId) {
        return """
                external_id,last_name,first_name,middle_name,phone,email,consent_type_code,form_code,form_version,\
                granted_at,valid_until,source,source_ref,third_party_inn,pdn_categories,document_ref,note
                %s,Заозёрская,Ольга,Петровна,+7 916 044-00-11,zaozerskaya@example.ru,PDN_PROCESSING,%s,1,\
                12.03.2025,,CLIENT_BASE_IMPORT,БАЗА-2019,,FIO;PHONE,,перенос из базы клиентов
                """
                .formatted(externalId, form.getCode());
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
