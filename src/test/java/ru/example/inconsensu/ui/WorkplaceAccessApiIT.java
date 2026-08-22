package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * Приёмка §16.2 на слое рабочего места: кто входит, что видит и как приходит отказ.
 *
 * <p>Права проверяются по адресам данных, а не по разметке: меню одностраничного приложения собирается в
 * браузере и спрятанный пункт ничего не запрещает. Единственная настоящая граница — ответ сервера, поэтому
 * закрытый раздел обязан отдать 403, а не данные «на всякий случай».
 *
 * <p>Отказ и вход без сессии проверяются телом, а не только кодом: приложение ждёт JSON, и перенаправление
 * на страницу входа оно приняло бы за успешный ответ с чужой вёрсткой (UI-0.9).
 *
 * <p>MockMvc берётся вместо сетевого клиента намеренно: тот прячет 302 и 403 за автоматическим переходом
 * по редиректу, и матрица прав перестала бы что-либо проверять.
 */
@AutoConfigureMockMvc
class WorkplaceAccessApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Приложение E: роли с рабочим местом. INTEGRATION сюда не входит — у неё только токен. */
    private static final List<RoleCode> EMPLOYEE_ROLES = List.of(
            RoleCode.ADMIN, RoleCode.DPO, RoleCode.LAWYER, RoleCode.MANAGER, RoleCode.MARKETING, RoleCode.AUDITOR);

    /**
     * Таблица §16.2 целиком: адрес раздела -> роли, которым он открыт. Всем прочим ролям положен 403.
     *
     * <p>Список — вся таблица, а не выборка: §16.5 требует подтверждать доступ «тестами на 403 для каждой
     * роли», и выборочные пары пропускали бы как раз ту роль, для которой правило написали неверно.
     *
     * <p>Адреса — те, с которых раздел начинает работу: приложение открывает экран этим запросом, и запрет
     * на нём закрывает раздел целиком.
     */
    private static final Map<String, Set<RoleCode>> SECTIONS = sectionsOfSection162();

    private static Map<String, Set<RoleCode>> sectionsOfSection162() {
        Set<RoleCode> everyEmployee = Set.copyOf(EMPLOYEE_ROLES);
        Map<String, Set<RoleCode>> sections = new LinkedHashMap<>();
        sections.put("/ui/api/dashboard", everyEmployee);
        sections.put("/ui/api/subjects", everyEmployee);
        sections.put("/ui/api/catalog/types", everyEmployee);
        sections.put("/ui/api/catalog/forms", everyEmployee);
        sections.put("/ui/api/third-parties", everyEmployee);
        sections.put("/ui/api/import", Set.of(RoleCode.DPO, RoleCode.ADMIN));
        sections.put("/ui/api/notifications/rules", Set.of(RoleCode.DPO, RoleCode.ADMIN));
        sections.put("/ui/api/webhooks", Set.of(RoleCode.ADMIN));
        sections.put("/ui/api/audit/events", Set.of(RoleCode.AUDITOR, RoleCode.DPO, RoleCode.ADMIN));
        sections.put("/ui/api/admin/users", Set.of(RoleCode.ADMIN));
        sections.put("/ui/api/admin/settings", Set.of(RoleCode.ADMIN, RoleCode.DPO));
        return sections;
    }

    @Test
    void employee_of_every_role_signs_in_and_gets_its_own_roles_from_me() throws Exception {
        for (RoleCode role : EMPLOYEE_ROLES) {
            AppUser user = accounts.create(role.name());

            String me = mockMvc.perform(get("/ui/api/me").session(loginAs(user)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(textOf(me, "login")).isEqualTo(user.getLogin());
            // По этому списку приложение собирает меню: лишний код открыл бы пункт, закрытый сервером.
            assertThat(rolesOf(me)).as("роли в /ui/api/me для %s", role).containsExactly(role.name());
            // UI-0.12: оболочка не может нарисовать шапку без названия оператора.
            assertThat(textOf(me, "operatorName")).isNotBlank();
        }
    }

    @Test
    void every_role_reaches_exactly_the_sections_of_section_16_2() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            MockHttpSession session = loginAs(role.name());
            for (Map.Entry<String, Set<RoleCode>> section : SECTIONS.entrySet()) {
                // INTEGRATION — служебная роль без рабочего места, для неё закрыт весь интерфейс.
                boolean allowed =
                        role != RoleCode.INTEGRATION && section.getValue().contains(role);

                MockHttpServletResponse response = mockMvc.perform(
                                get(section.getKey()).session(session))
                        .andReturn()
                        .getResponse();

                assertThat(response.getStatus())
                        .as("%s для роли %s", section.getKey(), role)
                        .isEqualTo(allowed ? 200 : 403);
                if (!allowed) {
                    assertProblemDetail(response, 403, section.getKey() + " для роли " + role);
                }
            }
        }
    }

    @Test
    void service_role_has_no_workplace() throws Exception {
        // §16.2, Приложение E: у роли INTEGRATION есть токен машинной цепочки, но рабочего места нет.
        MockHttpServletResponse response = mockMvc.perform(
                        get("/ui/api/me").session(loginAs(RoleCode.INTEGRATION.name())))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse();

        assertProblemDetail(response, 403, "/ui/api/me для роли INTEGRATION");
    }

    @Test
    void request_without_a_session_is_refused_with_a_body_not_a_login_page() throws Exception {
        // Приложение отправляет сотрудника на вход само; перенаправление вместо кода оно приняло бы за
        // успешный ответ и показало бы кусок HTML вместо данных (UI-0.9).
        mockMvc.perform(get("/ui/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"))
                .andExpect(jsonPath("$.status").value(401));

        for (String section : SECTIONS.keySet()) {
            MockHttpServletResponse response =
                    mockMvc.perform(get(section)).andReturn().getResponse();
            assertThat(response.getStatus()).as("%s без сессии", section).isEqualTo(401);
            assertThat(response.getContentAsString())
                    .as("тело ответа %s без сессии", section)
                    .contains("urn:inconsensu:error:unauthorized");
        }
    }

    @Test
    void wrong_password_does_not_open_the_workplace() throws Exception {
        AppUser user = accounts.create(RoleCode.MANAGER.name());

        MockHttpSession session = (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password("не тот пароль"))
                        .andExpect(unauthenticated())
                        .andExpect(redirectedUrl("/ui/login?error"))
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        // Неудачная попытка не оставляет сессии, которая что-то открывает: данные по-прежнему закрыты.
        MockHttpServletRequestBuilder request = get("/ui/api/me");
        if (session != null) {
            request = request.session(session);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"));
    }

    /**
     * FR-11.1, UI-1: перебор пароля через форму приводит к блокировке.
     *
     * <p>Счётчик неудач наращивал только REST-вход, а форму обрабатывает стандартный провайдер, который
     * лишь читает признак блокировки. Через `/ui/login` пароль можно было подбирать без ограничений, а
     * ветка страницы «слишком много попыток» была недостижима.
     */
    @Test
    void repeated_wrong_passwords_lock_the_account_and_the_answer_says_for_how_long() throws Exception {
        AppUser user = accounts.create(RoleCode.MANAGER.name());
        int allowedAttempts = 5;

        for (int attempt = 0; attempt < allowedAttempts; attempt++) {
            mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password("не тот пароль"))
                    .andExpect(unauthenticated());
        }

        // Учётная запись заблокирована: даже верный пароль не пускает, а ответ называет срок.
        mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrlPattern("/ui/login?error&locked&minutes=*"));
    }

    /** Вход формой (UI-1): дальше сессия предъявляется так же, как её предъявляет браузер. */
    private MockHttpSession loginAs(String roleCode) throws Exception {
        return loginAs(accounts.create(roleCode));
    }

    private MockHttpSession loginAs(AppUser user) throws Exception {
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andExpect(authenticated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /**
     * Отказ приходит машиночитаемым телом, а не страницей: приложение ждёт JSON (UI-0.9).
     *
     * <p>Точный код в поле {@code type} здесь не закрепляется намеренно. Отказ по роли выдаёт цепочка
     * Spring Security, а отказ по правам на методе — обработчик ошибок MVC, и коды у них сегодня разные.
     * Правило, за которое отвечает §16.2, от этого не зависит: закрытый раздел отвечает 403 телом с кодом
     * состояния, а не вёрсткой страницы входа или «Доступ закрыт».
     */
    private static void assertProblemDetail(MockHttpServletResponse response, int status, String where)
            throws Exception {
        String contentType = response.getContentType() == null ? "" : response.getContentType();
        assertThat(contentType).as("тип содержимого отказа %s", where).startsWith("application/problem+json");

        JsonNode problem = MAPPER.readTree(response.getContentAsString());
        assertThat(problem.path("type").asText()).as("код ошибки %s", where).startsWith("urn:inconsensu:error:");
        assertThat(problem.path("status").asInt()).as("код состояния %s", where).isEqualTo(status);
        // Причина не уточняется: рассказывать, чего не хватает, значит рассказывать о правах чужой роли.
        assertThat(problem.path("title").asText())
                .as("заголовок отказа %s", where)
                .isNotBlank();
    }

    /** Разбор ответа: фильтры JsonPath по значению элемента здесь не работают. */
    private static List<String> rolesOf(String json) throws Exception {
        JsonNode roles = MAPPER.readTree(json).get("roles");
        List<String> codes = new ArrayList<>();
        for (JsonNode role : roles) {
            codes.add(role.asText());
        }
        return codes;
    }

    private static String textOf(String json, String field) throws Exception {
        return MAPPER.readTree(json).get(field).asText();
    }
}
