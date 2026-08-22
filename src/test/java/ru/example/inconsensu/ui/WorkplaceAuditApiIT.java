package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * Приёмка UI-15 на слое рабочего места: события, журнал доступа к ПДн и проверка целостности.
 *
 * <p>Проверяется ответ сервера, а не разметка: отбор считает запрос к журналу, и «по клиенту» обязано
 * сужать выборку, а не прятать строки в браузере. Прежние тесты ходили по HTML Thymeleaf; правила от смены
 * интерфейса не изменились, поэтому переносятся сюда.
 *
 * <p>Имя сотрудника и адрес экрана в журнале доступа — тоже правила сервера: в колонке «кто» стоял UUID, а
 * обращение из интерфейса писалось как `/api/v1/subjects/{id}`, и аудитор не отличал работу сотрудника от
 * обращения интеграции.
 */
@AutoConfigureMockMvc
class WorkplaceAuditApiIT extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** §16.2, Приложение E: журнал читают аудитор, ответственный за ПДн и администратор. */
    private static final Set<RoleCode> AUDIT_ROLES = Set.of(RoleCode.AUDITOR, RoleCode.DPO, RoleCode.ADMIN);

    /** Адреса, с которых раздел аудита начинает работу: запрет на них закрывает раздел целиком. */
    private static final List<String> AUDIT_SECTIONS = List.of(
            "/ui/api/audit/events", "/ui/api/audit/access-log", "/ui/api/audit/options", "/ui/api/audit/integrity");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private Clock clock;

    @Test
    void events_are_filtered_by_object_type_event_type_author_client_and_period() throws Exception {
        Consent consent = advertisingConsent();
        UUID subjectId = consent.getSubjectId();
        AppUser manager = accounts.create(RoleCode.MANAGER.name());
        revoke(consent.getId(), loginAs(manager));

        MockHttpSession auditor = loginAs(RoleCode.AUDITOR.name());
        String bySubject = events(auditor, "subjectId=" + subjectId);

        // По клиенту видно всё, что с ним происходило: и его заведение, и согласия, и отзыв.
        assertThat(total(bySubject)).isGreaterThanOrEqualTo(3);
        assertThat(column(rows(bySubject), "aggregateType")).contains("subject", "consent");

        String consentsOnly = events(auditor, "subjectId=" + subjectId + "&aggregateType=consent");
        assertThat(column(rows(consentsOnly), "aggregateType")).containsOnly("consent");
        // Отбор именно сужает выборку: события самого клиента в неё уже не попадают.
        assertThat(total(consentsOnly)).isLessThan(total(bySubject));

        String revoked = events(auditor, "subjectId=" + subjectId + "&eventType=REVOKED");
        assertThat(total(revoked)).isEqualTo(1);
        assertThat(column(rows(revoked), "eventTypeRu")).containsOnly("Согласие отозвано");
        assertThat(column(rows(revoked), "aggregateId"))
                .containsOnly(consent.getId().toString());

        // Отзыв сделал сотрудник, а согласия завёл сервис: по автору эти события обязаны различаться.
        String byManager = events(auditor, "subjectId=" + subjectId + "&actorId=" + manager.getLogin());
        assertThat(column(rows(byManager), "actor")).containsOnly(manager.getLogin());
        assertThat(column(rows(byManager), "aggregateId"))
                .contains(consent.getId().toString());
        assertThat(total(byManager)).isLessThan(total(bySubject));

        // Период: сегодняшние события видны целиком, а окно в будущем пусто — иначе даты ничего не значат.
        LocalDate today = LocalDate.now(clock);
        String thisPeriod = "&from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        String laterPeriod = "&from=" + today.plusDays(30) + "&to=" + today.plusDays(31);
        assertThat(total(events(auditor, "subjectId=" + subjectId + thisPeriod)))
                .isEqualTo(total(bySubject));
        assertThat(total(events(auditor, "subjectId=" + subjectId + laterPeriod)))
                .isZero();

        // Чужой клиент в выборку не попадает вовсе.
        assertThat(total(events(auditor, "subjectId=" + UUID.randomUUID()))).isZero();
    }

    /**
     * FR-10.5, UI-15: в журнале доступа стоит имя сотрудника и адрес того экрана, который он открыл.
     *
     * <p>Сервисы называют свой эндпоинт строкой, и открытие карточки из интерфейса писалось как
     * `/api/v1/subjects/{id}`: аудитор не отличал работу сотрудника от обращения интеграции.
     */
    @Test
    void access_log_names_the_employee_and_the_screen_he_actually_opened() throws Exception {
        Consent consent = advertisingConsent();
        UUID subjectId = consent.getSubjectId();
        AppUser manager = accounts.create(RoleCode.MANAGER.name());

        // Карточка клиента — обращение к ПДн: оно обязано оставить след (FR-5.2).
        mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(loginAs(manager)))
                .andExpect(status().isOk());

        MockHttpSession auditor = loginAs(RoleCode.AUDITOR.name());
        String log = accessLog(auditor, "subjectId=" + subjectId);

        assertThat(total(log)).isPositive();
        // Раньше в колонке «кто» стоял идентификатор, а при входе через форму — пусто.
        assertThat(column(rows(log), "user")).containsOnly(manager.getFullName());
        assertThat(column(rows(log), "user")).doesNotContain(manager.getId().toString());
        assertThat(column(rows(log), "endpoint")).containsOnly("/ui/api/subjects/{id}");

        // Фильтр по адресу экрана — тот же запрос к базе: запись обязана находиться под ним, а не под API.
        assertThat(total(accessLog(auditor, "subjectId=" + subjectId + "&endpoint=/ui/api/subjects")))
                .isEqualTo(total(log));
        assertThat(total(accessLog(auditor, "subjectId=" + subjectId + "&endpoint=/api/v1")))
                .isZero();
    }

    @Test
    void integrity_check_starts_and_appears_in_the_list_of_runs() throws Exception {
        AppUser auditor = accounts.create(RoleCode.AUDITOR.name());
        MockHttpSession session = loginAs(auditor);

        // Без токена проверку не запустить: чужая страница не должна нагружать журнал за сотрудника.
        mockMvc.perform(post("/ui/api/audit/integrity").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"));

        String started = json(post("/ui/api/audit/integrity").session(session).with(csrf()));

        // FR-10.4: проверка идёт в фоне, поэтому ответ отдаёт запуск сразу — аудитор видит его в истории.
        JsonNode run = runStartedBy(started, auditor.getLogin());
        assertThat(run.get("id").asText()).isNotBlank();
        assertThat(run.get("startedAt").asText()).isNotBlank();
        assertThat(run.get("status").asText()).isIn("RUNNING", "DONE");

        String history = json(get("/ui/api/audit/integrity").session(session));
        assertThat(column(MAPPER.readTree(history), "id"))
                .contains(run.get("id").asText());
    }

    @Test
    void audit_is_open_to_the_auditor_the_dpo_and_the_admin_only() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            MockHttpSession session = loginAs(role.name());
            boolean allowed = AUDIT_ROLES.contains(role);

            for (String section : AUDIT_SECTIONS) {
                MockHttpServletResponse response = mockMvc.perform(get(section).session(session))
                        .andReturn()
                        .getResponse();

                assertThat(response.getStatus())
                        .as("%s для роли %s", section, role)
                        .isEqualTo(allowed ? 200 : 403);
                if (!allowed) {
                    assertProblemDetail(response, section + " для роли " + role);
                }
            }

            if (!allowed) {
                // Прятать кнопку недостаточно: запуск проверки закрыт тем же ролям, что и чтение журнала.
                mockMvc.perform(post("/ui/api/audit/integrity").session(session).with(csrf()))
                        .andExpect(status().isForbidden());
            }
        }
    }

    /**
     * Отказ приходит машиночитаемым телом, а не страницей: приложение ждёт JSON (UI-0.9).
     *
     * <p>Причина не уточняется намеренно: рассказывать, чего не хватает, значит рассказывать о правах чужой
     * роли.
     */
    private static void assertProblemDetail(MockHttpServletResponse response, String where) throws Exception {
        String contentType = response.getContentType() == null ? "" : response.getContentType();
        assertThat(contentType).as("тип содержимого отказа %s", where).startsWith("application/problem+json");

        JsonNode problem = MAPPER.readTree(response.getContentAsString());
        assertThat(problem.path("type").asText()).as("код ошибки %s", where).startsWith("urn:inconsensu:error:");
        assertThat(problem.path("status").asInt()).as("код состояния %s", where).isEqualTo(403);
        assertThat(problem.path("title").asText())
                .as("заголовок отказа %s", where)
                .isNotBlank();
    }

    private String events(MockHttpSession session, String query) throws Exception {
        return json(get("/ui/api/audit/events?" + query).session(session));
    }

    private String accessLog(MockHttpSession session, String query) throws Exception {
        return json(get("/ui/api/audit/access-log?" + query).session(session));
    }

    private String json(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void revoke(UUID consentId, MockHttpSession session) throws Exception {
        mockMvc.perform(
                        post("/ui/api/consents/" + consentId + "/revoke")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"reason":"Клиент попросил прекратить рекламу","revocationSource":"CALL_CENTER",
                                 "caseNumber":"OBR-2026-0011"}
                                """))
                .andExpect(status().isOk());
    }

    /** Разбор ответа: фильтры JsonPath по значению поля в этом проекте не работают. */
    private static List<String> column(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : array) {
            values.add(row.get(field).asText());
        }
        return values;
    }

    private static JsonNode rows(String json) throws Exception {
        return MAPPER.readTree(json).get("rows");
    }

    private static long total(String json) throws Exception {
        return MAPPER.readTree(json).get("total").asLong();
    }

    private static JsonNode runStartedBy(String json, String login) throws Exception {
        for (JsonNode run : MAPPER.readTree(json)) {
            if (login.equals(run.get("startedBy").asText())) {
                return run;
            }
        }
        throw new AssertionError("В истории проверок нет запуска, начатого сотрудником " + login);
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        return loginAs(accounts.create(roleCode));
    }

    private MockHttpSession loginAs(AppUser user) throws Exception {
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /** Согласие на рекламу по почте: его отзыв не тянет за собой каскад, и событие в журнале ровно одно. */
    private Consent advertisingConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-AUD-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-45", true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL,
                                "travin-aud-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                                true)));

        var result = registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        items,
                        Instant.now(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "заявка рабочего места",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000045",
                                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));

        UUID advertisingItemId = itemOf(form, "ADVERTISING_EMAIL").getId();
        return result.created().stream()
                .filter(consent -> advertisingItemId.equals(consent.getFormItemId()))
                .findFirst()
                .orElseThrow();
    }

    private static ConsentFormItem itemOf(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }
}
