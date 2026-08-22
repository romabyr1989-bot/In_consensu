package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * Приёмка UI-14, UI-16 и UI-17 на слое рабочего места: учётные записи, настройки оператора и подписки.
 *
 * <p>Прежние проверки ходили по HTML Thymeleaf и смотрели на подписи и кнопки. От смены интерфейса правила
 * администрирования не изменились, поэтому они переносятся сюда и закрепляются там, где живут: в ответе
 * сервера. Спрятанный пункт меню ничего не запрещает — единственная граница раздела это код ответа.
 *
 * <p>Пароль и секрет подписки проверяются отдельно: они не должны возвращаться ни в одном ответе (NFR-3),
 * а секрет к тому же выдаётся ровно один раз, и заменить его можно только на новый.
 */
@AutoConfigureMockMvc
class WorkplaceAdminApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private UserService users;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Пароли вымышленные: по ним же проверяется, что в ответах их нет ни в открытом виде, ни хешем. */
    private static final String FIRST_PASSWORD = "kettle-window-lantern-41";

    private static final String SECOND_PASSWORD = "lantern-window-kettle-92";

    private static final String FULL_NAME = "Ветлугина Мария Павловна";

    /** Список учётных записей общий на весь прогон, поэтому нужную строку ищем перебором страниц. */
    private static final int PAGE_SIZE = 200;

    // ---------- UI-17: учётные записи ----------

    @Test
    void account_is_created_edited_and_blocked_with_its_roles_kept() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        String login = "manager-adm-" + suffix();
        String email = "m.vetlugina-" + suffix() + "@example.ru";

        String created = createManagerAccount(admin, login, email);
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        assertThat(rolesOf(created)).containsExactly(RoleCode.MANAGER.name());

        // Заведение — не строка в ответе: запись видна в том же списке, который открывает администратор.
        JsonNode listed = rowOf(usersPageWith(admin, login), login);
        assertThat(listed.get("fullName").asText()).isEqualTo(FULL_NAME);
        assertThat(listed.get("active").asBoolean()).isTrue();

        String edited = mockMvc.perform(post("/ui/api/admin/users")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"id":"%s","fullName":"%s","email":"%s",
                                 "roles":["MANAGER","MARKETING"],"active":true}
                                """
                                        .formatted(id, FULL_NAME, email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(rolesOf(edited)).containsExactlyInAnyOrder(RoleCode.MANAGER.name(), RoleCode.MARKETING.name());

        // Роли сохранены по-настоящему: сотрудник входит и получает их в /ui/api/me, а не только в списке.
        String me = mockMvc.perform(get("/ui/api/me").session(signIn(login, FIRST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(login))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(rolesOf(me)).containsExactlyInAnyOrder(RoleCode.MANAGER.name(), RoleCode.MARKETING.name());

        mockMvc.perform(post("/ui/api/admin/users")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"id":"%s","fullName":"%s","email":"%s",
                                 "roles":["MANAGER","MARKETING"],"active":false}
                                """
                                        .formatted(id, FULL_NAME, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // FR-11.1: блокировка — не отметка в таблице. С верным паролем вход больше не открывается.
        mockMvc.perform(formLogin("/ui/login").user(login).password(FIRST_PASSWORD))
                .andExpect(unauthenticated());
        assertThat(rowOf(usersPageWith(admin, login), login).get("active").asBoolean())
                .isFalse();
    }

    @Test
    void password_reset_lets_the_employee_in_with_the_new_password_only() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        String login = "manager-adm-" + suffix();
        UUID id = UUID.fromString(
                MAPPER.readTree(createManagerAccount(admin, login, "m.vetlugina-" + suffix() + "@example.ru"))
                        .get("id")
                        .asText());
        signIn(login, FIRST_PASSWORD);

        mockMvc.perform(post("/ui/api/admin/users/" + id + "/reset-password")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(SECOND_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Пароль сброшен"));

        // Прежний пароль перестаёт работать сразу: сброс не добавляет второй, а заменяет.
        mockMvc.perform(formLogin("/ui/login").user(login).password(FIRST_PASSWORD))
                .andExpect(unauthenticated());
        signIn(login, SECOND_PASSWORD);
    }

    @Test
    void password_never_appears_in_any_answer_about_the_account() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        String login = "manager-adm-" + suffix();

        String created = createManagerAccount(admin, login, "m.vetlugina-" + suffix() + "@example.ru");
        UUID id = UUID.fromString(MAPPER.readTree(created).get("id").asText());
        String listed = usersPageWith(admin, login);
        // NFR-3: в базе лежит хеш, а не пароль, и в ответы не попадает ни то ни другое.
        String hashAtCreation = users.get(id).getPasswordHash();

        String reset = mockMvc.perform(post("/ui/api/admin/users/" + id + "/reset-password")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(SECOND_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String hashAfterReset = users.get(id).getPasswordHash();

        assertThat(hashAtCreation).isNotEqualTo(FIRST_PASSWORD).isNotEqualTo(hashAfterReset);
        assertThat(hashAfterReset).isNotEqualTo(SECOND_PASSWORD);

        for (String answer : List.of(created, listed, reset)) {
            assertThat(answer)
                    .doesNotContain(FIRST_PASSWORD)
                    .doesNotContain(SECOND_PASSWORD)
                    .doesNotContain(hashAtCreation)
                    .doesNotContain(hashAfterReset);
            JsonNode body = MAPPER.readTree(answer);
            assertThat(body.findValue("password")).as("поле password в ответе").isNull();
            assertThat(body.findValue("passwordHash"))
                    .as("поле passwordHash в ответе")
                    .isNull();
        }
    }

    // ---------- UI-16: настройки оператора ----------

    @Test
    void settings_are_grouped_and_named_in_russian() throws Exception {
        String settings = settingsAs(loginAs(RoleCode.ADMIN.name()));

        // UI-16 перечисляет настройки группами в своём порядке, а не одним списком.
        assertThat(groupTitles(settings))
                .containsExactly(
                        "Реквизиты оператора",
                        "Ответственный за ПДн",
                        "Работа системы",
                        "Уведомления",
                        "Выгрузки партнёрам",
                        "Самообслуживание клиента",
                        "Брендирование",
                        "Хранение и удаление");

        // UI-0.4: сотруднику показывают подпись, а не ключ настройки вроде «inconsensu.export.ttl».
        for (JsonNode group : MAPPER.readTree(settings).get("groups")) {
            for (JsonNode setting : group.get("settings")) {
                String key = setting.get("key").asText();
                assertThat(setting.get("label").asText())
                        .as("подпись настройки %s", key)
                        .isNotBlank()
                        .isNotEqualTo(key);
            }
        }

        assertThat(labelOf(settings, "inconsensu.status.expiring-days"))
                .isEqualTo("Порог «заканчивается через N дней»");
        assertThat(labelOf(settings, "inconsensu.export.ttl")).isEqualTo("Срок жизни ссылки");
        assertThat(labelOf(settings, "inconsensu.selfservice.auth-mode")).isEqualTo("Режим аутентификации");
        assertThat(labelOf(settings, "branding.primary-color")).isEqualTo("Основной цвет");
    }

    @Test
    void setting_read_at_startup_is_shown_but_marked_unchangeable() throws Exception {
        String settings = settingsAs(loginAs(RoleCode.ADMIN.name()));

        // ADR-0084: таймзона прочитана при запуске, по ней уже посчитаны сроки. Экран её показывает, но
        // обещать правку не должен — иначе поле есть, а настройки за ним нет.
        JsonNode timezone = settingOf(settings, "inconsensu.timezone");
        assertThat(timezone.get("readOnly").asBoolean()).isTrue();
        assertThat(timezone.get("value").asText()).isNotBlank();
        assertThat(timezone.get("hint").asText()).isNotBlank();

        assertThat(settingOf(settings, "operator.name").get("readOnly").asBoolean())
                .isFalse();
    }

    @Test
    void operator_setting_is_saved_and_the_answer_shows_who_changed_it() throws Exception {
        AppUser admin = accounts.create(RoleCode.ADMIN.name());
        MockHttpSession session = loginAs(admin);
        int changesBefore = MAPPER.readTree(settingsAs(session)).get("history").size();
        String ogrn = fictionalOgrn();

        String saved = mockMvc.perform(post("/ui/api/admin/settings")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator.ogrn\":\"%s\"}".formatted(ogrn)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(settingOf(saved, "operator.ogrn").get("value").asText()).isEqualTo(ogrn);

        // UI-16: история изменений — часть того же ответа, за ней не нужно уходить в журнал аудита.
        JsonNode history = MAPPER.readTree(saved).get("history");
        assertThat(history.size()).isGreaterThan(changesBefore);
        JsonNode mine = changeBy(history, admin.getLogin());
        assertThat(mine.get("description").asText()).isEqualTo("Изменены настройки");
        assertThat(mine.get("at").asText()).isNotBlank();

        // Правка живёт в базе, а не в ответе: следующий запрос экрана отдаёт уже новое значение.
        assertThat(settingOf(settingsAs(session), "operator.ogrn").get("value").asText())
                .isEqualTo(ogrn);
    }

    // ---------- UI-14: подписки на события ----------

    @Test
    void subscription_is_created_and_its_secret_is_shown_once() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        String name = "CRM " + suffix();

        String created = saveSubscription(admin, subscriptionBody(name));
        String secret = MAPPER.readTree(created).get("secret").asText();
        assertThat(secret).isNotBlank();

        JsonNode row = rowNamed(MAPPER.readTree(created).get("rows"), name);
        assertThat(row.get("active").asBoolean()).isTrue();
        assertThat(textsOf(row.get("eventTypes")))
                .containsExactlyInAnyOrder(
                        ru.example.inconsensu.common.domain.EventTypes.CONSENT_GRANTED,
                        ru.example.inconsensu.common.domain.EventTypes.CONSENT_REVOKED);

        // FR-9.3: секрет отдаётся ровно один раз. Ни список подписок, ни их правка его больше не знают.
        assertThat(MAPPER.readTree(created).get("rows").toString()).doesNotContain(secret);
        assertThat(webhooksAs(admin)).doesNotContain(secret);

        String renamed = saveSubscription(
                admin,
                """
                {"subscriptionId":"%s","name":"%s","url":"https://crm.example.ru/hooks/%s",
                 "eventTypes":["consent.granted"]}
                """
                        .formatted(row.get("id").asText(), name, suffix()));
        JsonNode secretAfterEdit = MAPPER.readTree(renamed).path("secret");
        assertThat(secretAfterEdit.isNull() || secretAfterEdit.isMissingNode())
                .as("правка подписки не выдаёт секрет заново")
                .isTrue();
    }

    @Test
    void subscription_is_switched_off_and_every_new_secret_differs_from_the_previous_one() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        String name = "CRM " + suffix();

        String created = saveSubscription(admin, subscriptionBody(name));
        String firstSecret = MAPPER.readTree(created).get("secret").asText();
        UUID id = UUID.fromString(
                rowNamed(MAPPER.readTree(created).get("rows"), name).get("id").asText());

        // UI-14: подписку выключают, а не удаляют — история доставок остаётся доказательством настройки.
        String deactivated = mockMvc.perform(post("/ui/api/webhooks/" + id + "/deactivate")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(rowNamed(MAPPER.readTree(deactivated), name).get("active").asBoolean())
                .isFalse();

        String secondSecret = rotateSecret(admin, id);
        String thirdSecret = rotateSecret(admin, id);

        // Замена обязана давать новый секрет: иначе прежний потребитель продолжал бы проверять подпись.
        assertThat(secondSecret).isNotBlank().isNotEqualTo(firstSecret);
        assertThat(thirdSecret).isNotBlank().isNotEqualTo(secondSecret).isNotEqualTo(firstSecret);
        assertThat(webhooksAs(admin))
                .doesNotContain(firstSecret)
                .doesNotContain(secondSecret)
                .doesNotContain(thirdSecret);
    }

    @Test
    void subscription_is_not_created_without_a_csrf_token() throws Exception {
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());
        int before = MAPPER.readTree(webhooksAs(admin)).size();

        // Без токена запрос не проходит: иначе чужая страница завела бы подписку от имени администратора.
        mockMvc.perform(post("/ui/api/webhooks")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionBody("CRM " + suffix())))
                // Код зависит от того, был ли токен уже выдан этой сессии: пропавший токен сервер считает
                // истёкшей сессией (401), подменённый — отказом в правах (403). Важно, что подписки нет.
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.startsWith("urn:inconsensu:error:")));

        assertThat(MAPPER.readTree(webhooksAs(admin)).size()).isEqualTo(before);
    }

    // ---------- §16.2: кому открыт раздел ----------

    @Test
    void accounts_are_open_to_the_administrator_only() throws Exception {
        UUID someone = UUID.randomUUID();
        for (RoleCode role : RoleCode.values()) {
            if (role == RoleCode.ADMIN) {
                continue;
            }
            MockHttpSession session = loginAs(role.name());
            forbidden(get("/ui/api/admin/users").session(session), "чтение учётных записей ролью " + role);
            forbidden(
                    post("/ui/api/admin/users")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    "заведение учётной записи ролью " + role);
            forbidden(
                    post("/ui/api/admin/users/" + someone + "/reset-password")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    "сброс пароля ролью " + role);
        }

        mockMvc.perform(get("/ui/api/admin/users").session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk());
    }

    @Test
    void settings_are_open_to_the_administrator_and_the_data_protection_officer() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            if (role == RoleCode.ADMIN || role == RoleCode.DPO) {
                continue;
            }
            MockHttpSession session = loginAs(role.name());
            forbidden(get("/ui/api/admin/settings").session(session), "чтение настроек ролью " + role);
            forbidden(
                    post("/ui/api/admin/settings")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operator.ogrn\":\"%s\"}".formatted(fictionalOgrn())),
                    "правка настроек ролью " + role);
        }

        // Ответственный за ПДн ведёт раздел наравне с администратором: раздел открыт ему и на запись.
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String ogrn = fictionalOgrn();
        String saved = mockMvc.perform(post("/ui/api/admin/settings")
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator.ogrn\":\"%s\"}".formatted(ogrn)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(settingOf(saved, "operator.ogrn").get("value").asText()).isEqualTo(ogrn);
    }

    @Test
    void subscriptions_are_open_to_the_administrator_only() throws Exception {
        UUID someSubscription = UUID.randomUUID();
        for (RoleCode role : RoleCode.values()) {
            if (role == RoleCode.ADMIN) {
                continue;
            }
            MockHttpSession session = loginAs(role.name());
            forbidden(get("/ui/api/webhooks").session(session), "чтение подписок ролью " + role);
            forbidden(
                    post("/ui/api/webhooks")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    "заведение подписки ролью " + role);
            forbidden(
                    post("/ui/api/webhooks/" + someSubscription + "/deactivate")
                            .session(session)
                            .with(csrf()),
                    "выключение подписки ролью " + role);
            forbidden(
                    post("/ui/api/webhooks/" + someSubscription + "/rotate-secret")
                            .session(session)
                            .with(csrf()),
                    "замена секрета подписки ролью " + role);
        }
    }

    // ---------- вспомогательное ----------

    /**
     * Отказ приходит машиночитаемым телом, а не страницей: приложение ждёт JSON (UI-0.9).
     *
     * <p>Точный код в поле {@code type} не закрепляется: отказ по роли выдаёт цепочка Spring Security, а
     * отказ по правам на методе — обработчик ошибок MVC, и коды у них сегодня разные.
     */
    private void forbidden(MockHttpServletRequestBuilder request, String where) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).as(where).isEqualTo(403);
        assertThat(response.getContentType() == null ? "" : response.getContentType())
                .as("тип содержимого отказа: %s", where)
                .startsWith("application/problem+json");
        JsonNode problem = MAPPER.readTree(response.getContentAsString());
        assertThat(problem.path("status").asInt())
                .as("код состояния отказа: %s", where)
                .isEqualTo(403);
        assertThat(problem.path("type").asText())
                .as("код ошибки отказа: %s", where)
                .startsWith("urn:inconsensu:error:");
        // Причина не уточняется: рассказывать, чего не хватает, значит рассказывать о правах чужой роли.
        assertThat(problem.path("title").asText())
                .as("заголовок отказа: %s", where)
                .isNotBlank();
    }

    private String createManagerAccount(MockHttpSession admin, String login, String email) throws Exception {
        return mockMvc.perform(post("/ui/api/admin/users")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"login":"%s","password":"%s","fullName":"%s","email":"%s",
                                 "roles":["MANAGER"],"active":true}
                                """
                                        .formatted(login, FIRST_PASSWORD, FULL_NAME, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(login))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Страница списка, на которой оказалась нужная учётная запись: база общая на весь прогон. */
    private String usersPageWith(MockHttpSession session, String login) throws Exception {
        for (int page = 0; ; page++) {
            String body = mockMvc.perform(get("/ui/api/admin/users")
                            .session(session)
                            .param("page", String.valueOf(page))
                            .param("size", String.valueOf(PAGE_SIZE)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode found = MAPPER.readTree(body);
            for (JsonNode row : found.get("rows")) {
                if (login.equals(row.get("login").asText())) {
                    return body;
                }
            }
            if ((long) (page + 1) * PAGE_SIZE >= found.get("total").asLong()) {
                throw new AssertionError("В списке учётных записей нет " + login);
            }
        }
    }

    private String settingsAs(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/ui/api/admin/settings").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String webhooksAs(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/ui/api/webhooks").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String saveSubscription(MockHttpSession admin, String body) throws Exception {
        return mockMvc.perform(post("/ui/api/webhooks")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String rotateSecret(MockHttpSession admin, UUID id) throws Exception {
        String rotated = mockMvc.perform(post("/ui/api/webhooks/" + id + "/rotate-secret")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(rotated).get("secret").asText();
    }

    /** Адрес вымышленный: подписка никуда не ходит, пока её не отправят тестовым событием. */
    private static String subscriptionBody(String name) {
        return """
                {"name":"%s","url":"https://crm.example.ru/hooks/%s",
                 "eventTypes":["consent.granted","consent.revoked"]}
                """
                .formatted(name, suffix());
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        return loginAs(accounts.create(roleCode));
    }

    private MockHttpSession loginAs(AppUser user) throws Exception {
        return signIn(user.getLogin(), TestAccounts.PASSWORD);
    }

    private MockHttpSession signIn(String login, String password) throws Exception {
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(login).password(password))
                        .andExpect(authenticated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /** Разбор ответа: фильтры JsonPath по значению поля в этом проекте не работают. */
    private static JsonNode rowOf(String usersPage, String login) throws Exception {
        for (JsonNode row : MAPPER.readTree(usersPage).get("rows")) {
            if (login.equals(row.get("login").asText())) {
                return row;
            }
        }
        throw new AssertionError("В списке учётных записей нет " + login);
    }

    private static JsonNode rowNamed(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.get("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("В списке подписок нет «" + name + "»");
    }

    private static JsonNode settingOf(String settings, String key) throws Exception {
        for (JsonNode group : MAPPER.readTree(settings).get("groups")) {
            for (JsonNode setting : group.get("settings")) {
                if (key.equals(setting.get("key").asText())) {
                    return setting;
                }
            }
        }
        throw new AssertionError("В ответе нет настройки " + key);
    }

    private static String labelOf(String settings, String key) throws Exception {
        return settingOf(settings, key).get("label").asText();
    }

    private static List<String> groupTitles(String settings) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JsonNode group : MAPPER.readTree(settings).get("groups")) {
            titles.add(group.get("title").asText());
        }
        return titles;
    }

    /** Своя запись в истории: база общая на прогон, и «последняя» запись может оказаться чужой. */
    private static JsonNode changeBy(JsonNode history, String actor) {
        for (JsonNode change : history) {
            if (actor.equals(change.get("actor").asText())) {
                return change;
            }
        }
        throw new AssertionError("В истории изменений нет записи автора " + actor);
    }

    private static List<String> rolesOf(String json) throws Exception {
        return textsOf(MAPPER.readTree(json).get("roles"));
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    /** ОГРН вымышленный и разный в каждом прогоне: иначе «сохранилось» нельзя отличить от «уже было». */
    private static String fictionalOgrn() {
        return "1027" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
