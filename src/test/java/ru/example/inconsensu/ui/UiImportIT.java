package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
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
 * Приёмка UI-12: загрузка файла, пробный запуск и переход к боевому импорту.
 *
 * <p>Ключевая проверка — переключатель режима: боевой импорт из интерфейса был недостижим, потому что
 * снятый чекбокс браузер не присылает, а параметр по умолчанию означал «пробный».
 */
@AutoConfigureMockMvc
class UiImportIT extends AbstractIntegrationTest {

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
    void upload_page_offers_the_format_the_sample_and_the_source_dictionary() throws Exception {
        MockHttpSession dpo = loginAs();

        mockMvc.perform(get("/ui/import").session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Описание формата")))
                .andExpect(content().string(containsString("/assets/sample-import.csv")))
                // UI-12: источник выбирается из справочника, а не вводится строкой.
                .andExpect(content().string(containsString("<select class=\"form-select\" id=\"source\"")))
                .andExpect(content().string(containsString("Импорт базы клиентов")));

        mockMvc.perform(get("/ui/import/format").session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("consent_type_code")))
                .andExpect(content().string(containsString("Дата без времени")));
    }

    @Test
    void switching_the_toggle_off_starts_a_real_import() throws Exception {
        String externalId = "CRM-UI-" + UUID.randomUUID().toString().substring(0, 8);

        ImportJob job = upload(externalId, "off");

        assertThat(job.isDryRun())
                .as("снятый переключатель означает боевой импорт")
                .isFalse();
        assertThat(job.getImported()).isEqualTo(1);
        assertThat(subjects.findByExternalId(externalId)).isPresent();
    }

    @Test
    void request_without_the_form_marker_stays_a_dry_run() throws Exception {
        String externalId = "CRM-UI-" + UUID.randomUUID().toString().substring(0, 8);

        ImportJob job = upload(externalId, "no-marker");

        // Запрос пришёл не из формы интерфейса: молча запускать боевой импорт нельзя.
        assertThat(job.isDryRun()).isTrue();
        assertThat(subjects.findByExternalId(externalId)).isEmpty();
    }

    @Test
    void successful_dry_run_offers_the_button_that_imports_the_same_file() throws Exception {
        String externalId = "CRM-UI-" + UUID.randomUUID().toString().substring(0, 8);
        MockHttpSession dpo = loginAs();
        ImportJob dry = upload(externalId, "on");

        assertThat(dry.isDryRun()).isTrue();
        mockMvc.perform(get("/ui/import/" + dry.getId()).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Запустить импорт")))
                .andExpect(content().string(containsString("будет импортирован в боевом режиме")));

        String redirect = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/ui/import/" + dry.getId() + "/run")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        UUID realJobId = UUID.fromString(redirect.substring(redirect.lastIndexOf('/') + 1));
        ImportJob real = awaitFinished(realJobId);
        assertThat(real.isDryRun()).isFalse();
        assertThat(real.getImported()).isEqualTo(1);
        assertThat(subjects.findByExternalId(externalId)).isPresent();

        // Файл после запуска не хранится: повторное нажатие не должно импортировать тот же файл дважды.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/ui/import/" + dry.getId() + "/run")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(imports.get(dry.getId()).getPayload()).isNull();
    }

    @Test
    void row_errors_are_downloaded_as_csv() throws Exception {
        MockHttpSession dpo = loginAs();
        String broken =
                """
                external_id,last_name,first_name,consent_type_code,granted_at,source,note
                ,Травин,Иван,PDN_PROCESSING,12.03.2025,CLIENT_BASE_IMPORT,перенос
                """;
        ImportJob job = awaitFinished(uploadContent(broken, "on"));
        assertThat(job.getRejected()).isPositive();

        mockMvc.perform(get("/ui/import/" + job.getId()).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Скачать отчёт CSV")));

        String csv = mockMvc.perform(
                        get("/ui/import/" + job.getId() + "/report.csv").session(dpo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("Строка;Поле;Причина").contains("external_id");
    }

    private ImportJob upload(String externalId, String toggle) throws Exception {
        return awaitFinished(uploadContent(csv(externalId), toggle));
    }

    private UUID uploadContent(String content, String toggle) throws Exception {
        var request = multipart("/ui/import")
                .file(new MockMultipartFile(
                        "file", "clients.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .session(loginAs())
                .with(csrf())
                .param("source", "CLIENT_BASE_IMPORT");
        if (!"no-marker".equals(toggle)) {
            request = request.param("dryRunSubmitted", "true");
        }
        if ("on".equals(toggle)) {
            request = request.param("dryRun", "true");
        }
        String redirect = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return UUID.fromString(redirect.substring(redirect.lastIndexOf('/') + 1));
    }

    /** Импорт идёт в фоне: страница задачи опрашивает прогресс, тест — состояние задачи. */
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

    private String csv(String externalId) {
        return """
                external_id,last_name,first_name,middle_name,phone,email,consent_type_code,form_code,form_version,\
                granted_at,valid_until,source,source_ref,third_party_inn,pdn_categories,document_ref,note
                %s,Заозёрская,Ольга,Петровна,+7 916 044-00-11,zaozerskaya@example.ru,PDN_PROCESSING,%s,1,\
                12.03.2025,,CLIENT_BASE_IMPORT,БАЗА-2019,,FIO;PHONE,,перенос из базы клиентов
                """
                .formatted(externalId, form.getCode());
    }

    private MockHttpSession loginAs() throws Exception {
        AppUser user = accounts.create(RoleCode.DPO.name());
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
