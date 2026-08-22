package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.domain.ConsentForm;
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
 * Приёмка UI-3 и UI-4 на слое рабочего места: поиск клиента, карточка, контакты и заведение.
 *
 * <p>Проверяется то, что действительно отдаёт сервер одностраничному приложению: расчёт каналов,
 * маскирование контактов и запись в журнал доступа к ПДн живут здесь, а не в разметке. Прежние тесты
 * ходили по HTML Thymeleaf; правила от смены интерфейса не изменились, поэтому переносятся сюда.
 */
@AutoConfigureMockMvc
class WorkplaceSubjectApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ru.example.inconsensu.audit.infrastructure.PdnAccessLogRepository accessLog;

    @Test
    void search_keeps_personal_data_out_of_the_address_bar() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        // Ищем по внешнему идентификатору: он уникален, а однофамильцев в базе может быть много.
        String externalId = subjects.get(consent.getSubjectId()).getExternalId();

        // Запрос уходит телом POST: телефон, почта и ФИО не должны попадать ни в адрес, ни в журнал
        // веб-сервера (UI-0.10). GET по тому же адресу поиска не существует.
        mockMvc.perform(post("/ui/api/subjects/search")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"%s\"}".formatted(externalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Травин Иван Сергеевич"))
                .andExpect(jsonPath("$[0].externalId").value(externalId))
                .andExpect(jsonPath("$[0].active").value(2));

        // Тем же адресом по GET данные не отдаются: иначе запрос с ПДн можно было бы собрать ссылкой.
        mockMvc.perform(get("/ui/api/subjects/search").session(session)).andExpect(status().is4xxClientError());

        assertThat(consent.getSubjectId()).isNotNull();
    }

    @Test
    void one_search_leaves_exactly_one_record_in_the_personal_data_access_log() throws Exception {
        registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        long before = accessLog.count();

        mockMvc.perform(post("/ui/api/subjects/search")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Травин\"}"))
                .andExpect(status().isOk());

        // FR-10.2: одна операция — одна запись, иначе журнал доступа перестаёт быть читаемым.
        assertThat(accessLog.count()).isEqualTo(before + 1);
    }

    @Test
    void contact_is_masked_until_the_employee_asks_to_reveal_it() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        UUID subjectId = consent.getSubjectId();

        String card = mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayReveal").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var phone = contactOf(card, "PHONE");
        assertThat(phone.get("masked").asBoolean()).isTrue();
        assertThat(phone.get("value").asText()).isEqualTo("+7 9** ***-**-45");

        // Раскрытие — отдельное действие: POST, потому что оно оставляет запись в журнале доступа.
        mockMvc.perform(post("/ui/api/subjects/" + subjectId + "/reveal")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(false))
                // Пробелы в номере неразрывные: иначе в узкой колонке таблицы он разрывался на три строки.
                .andExpect(jsonPath("$.value").value("+7\u00A0(916)\u00A0000-00-45"));
    }

    @Test
    void card_shows_channels_transfers_and_history_of_the_client() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        UUID subjectId = consent.getSubjectId();

        String card = mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                // UI-4: плитки каналов идут в порядке макета и объясняют запрет словами.
                .andExpect(jsonPath("$.channels[0].nameRu").value("Телефонный звонок"))
                .andExpect(jsonPath("$.consents.length()").value(2))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var email = channelOf(card, "EMAIL");
        assertThat(email.get("allowed").asBoolean()).isTrue();
        assertThat(email.get("reason").asText()).isEmpty();

        mockMvc.perform(get("/ui/api/subjects/" + subjectId + "/history").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.entries[0].actorRu").isNotEmpty());

        mockMvc.perform(post("/ui/api/subjects/" + subjectId + "/history/verify")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void superseded_toggle_adds_superseded_consents() throws Exception {
        Consent first = registerConsent();
        String externalId = subjectExternalId(first.getSubjectId());
        registerConsentFor(externalId);
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());

        mockMvc.perform(get("/ui/api/subjects/" + first.getSubjectId()).session(session))
                .andExpect(jsonPath("$.consents.length()").value(2));

        // UI-4: переключатель дозапрашивает заменённые версии — по умолчанию виден текущий срез.
        mockMvc.perform(get("/ui/api/subjects/" + first.getSubjectId() + "?superseded=true")
                        .session(session))
                .andExpect(jsonPath("$.consents.length()").value(4));
    }

    @Test
    void filter_alone_opens_the_list_without_a_search_query() throws Exception {
        registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());

        // UI-2: плитка главной открывает список по отбору, поискового запроса у сотрудника нет.
        mockMvc.perform(get("/ui/api/subjects?status=ACTIVE").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.rows[0].fullName").isNotEmpty());

        // Пустой отбор не выдаёт всю базу: экран объясняет, что нужно сделать (UI-0.6).
        mockMvc.perform(get("/ui/api/subjects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.hint").isNotEmpty());
    }

    @Test
    void client_is_created_and_the_same_external_id_updates_him() throws Exception {
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        String externalId = "CRM-API-" + UUID.randomUUID().toString().substring(0, 8);
        String body =
                """
                {"externalId":"%s","lastName":"Северцева","firstName":"Ольга","middleName":"Дмитриевна",
                 "birthDate":"1988-04-02","phone":"+7 916 000-11-22","email":"olga-%s@example.ru"}
                """
                        .formatted(externalId, UUID.randomUUID().toString().substring(0, 6));

        String created = mockMvc.perform(post("/ui/api/subjects")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Клиент сохранён"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // FR-4.4: повторная отправка того же внешнего идентификатора правит запись, а не создаёт вторую.
        mockMvc.perform(post("/ui/api/subjects")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Ольга", "Ольга-Мария")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(idOf(created)));
    }

    @Test
    void card_is_downloaded_as_pdf_without_personal_data_in_the_file_name() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());

        mockMvc.perform(get("/ui/api/subjects/" + consent.getSubjectId() + "/card.pdf")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(
                                "Content-Disposition",
                                "attachment; filename=\"consent-card-" + consent.getSubjectId() + ".pdf\""));
    }

    @Test
    void service_role_has_no_workplace() throws Exception {
        MockHttpSession session = loginAs(RoleCode.INTEGRATION.name());

        // Приложение E: учётная запись машинной цепочки не работает в интерфейсе сотрудника.
        mockMvc.perform(get("/ui/api/me").session(session)).andExpect(status().isForbidden());
    }

    /** Разбор ответа: фильтры JsonPath по значению поля здесь не работают, а порядок элементов не задан. */
    private static com.fasterxml.jackson.databind.JsonNode contactOf(String json, String type) throws Exception {
        return elementOf(json, "contacts", "type", type);
    }

    private static com.fasterxml.jackson.databind.JsonNode channelOf(String json, String channel) throws Exception {
        return elementOf(json, "channels", "channel", channel);
    }

    private static com.fasterxml.jackson.databind.JsonNode elementOf(
            String json, String array, String field, String value) throws Exception {
        var node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(array);
        for (var element : node) {
            if (value.equals(element.get(field).asText())) {
                return element;
            }
        }
        throw new AssertionError("В ответе нет элемента " + array + "." + field + " = " + value);
    }

    private String idOf(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json)
                .get("id")
                .asText();
    }

    @Autowired
    private SubjectService subjects;

    private String subjectExternalId(UUID subjectId) {
        return subjects.get(subjectId).getExternalId();
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

    private Consent registerConsent() {
        return registerConsentFor(null);
    }

    private Consent registerConsentFor(String existingExternalId) {
        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                existingExternalId == null
                        ? "CRM-API-" + UUID.randomUUID().toString().substring(0, 8)
                        : existingExternalId,
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-45", true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL,
                                "travin-api-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
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
        return result.created().get(0);
    }
}
