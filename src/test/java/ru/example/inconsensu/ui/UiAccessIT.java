package ru.example.inconsensu.ui;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * §16.2: меню и доступ по ролям. Закрытая страница обязана отвечать 403, а не показывать данные
 * «на всякий случай».
 *
 * <p>Проверка идёт через MockMvc: работает настоящая цепочка Spring Security с сессией и CSRF, но без
 * сетевого клиента, который прячет 302 и 403 за автоматическим переходом по редиректу.
 */
@AutoConfigureMockMvc
class UiAccessIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    /** Вход формой (UI-1): дальше сессия предъявляется как настоящим браузером. */
    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andExpect(authenticated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private int statusOf(String path, MockHttpSession session) throws Exception {
        return mockMvc.perform(get(path).session(session))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void employee_of_every_role_can_sign_in_and_see_the_dashboard() throws Exception {
        for (RoleCode role : List.of(
                RoleCode.ADMIN,
                RoleCode.DPO,
                RoleCode.LAWYER,
                RoleCode.MANAGER,
                RoleCode.MARKETING,
                RoleCode.AUDITOR)) {
            mockMvc.perform(get("/ui/").session(loginAs(role.name())))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Действующих согласий")));
        }
    }

    @Test
    void service_role_has_no_workplace() throws Exception {
        // §16.2: у роли INTEGRATION есть токен, но нет рабочего места — интерфейс для неё закрыт.
        mockMvc.perform(get("/ui/").session(loginAs(RoleCode.INTEGRATION.name())))
                .andExpect(status().isForbidden());
    }

    /**
     * Таблица §16.2 целиком: раздел меню -> роли, которым он открыт. Всем прочим ролям положен 403.
     *
     * <p>Список — вся таблица, а не выборка: §16.5 требует подтверждать доступ «тестами на 403 для каждой
     * роли», и выборочные пары пропускали бы как раз ту роль, для которой правило написали неверно.
     */
    private static final Map<String, Set<RoleCode>> MENU = menuOfSection162();

    private static Map<String, Set<RoleCode>> menuOfSection162() {
        Set<RoleCode> everyEmployee = Set.of(
                RoleCode.ADMIN, RoleCode.DPO, RoleCode.LAWYER, RoleCode.MANAGER, RoleCode.MARKETING, RoleCode.AUDITOR);
        Map<String, Set<RoleCode>> menu = new LinkedHashMap<>();
        menu.put("/ui/", everyEmployee);
        menu.put("/ui/subjects", everyEmployee);
        menu.put("/ui/catalog/types", everyEmployee);
        menu.put("/ui/catalog/forms", everyEmployee);
        menu.put("/ui/third-parties", everyEmployee);
        menu.put("/ui/import", Set.of(RoleCode.DPO, RoleCode.ADMIN));
        menu.put("/ui/notifications", Set.of(RoleCode.DPO, RoleCode.ADMIN));
        menu.put("/ui/webhooks", Set.of(RoleCode.ADMIN));
        menu.put("/ui/audit", Set.of(RoleCode.AUDITOR, RoleCode.DPO, RoleCode.ADMIN));
        menu.put("/ui/admin/users", Set.of(RoleCode.ADMIN));
        menu.put("/ui/admin/settings", Set.of(RoleCode.ADMIN, RoleCode.DPO));
        return menu;
    }

    @Test
    void every_role_sees_exactly_the_sections_of_section_16_2() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            MockHttpSession session = loginAs(role.name());
            for (Map.Entry<String, Set<RoleCode>> section : MENU.entrySet()) {
                // INTEGRATION — служебная роль без рабочего места, для неё закрыт весь интерфейс.
                boolean allowed =
                        role != RoleCode.INTEGRATION && section.getValue().contains(role);
                org.assertj.core.api.Assertions.assertThat(statusOf(section.getKey(), session))
                        .as("%s для роли %s", section.getKey(), role)
                        .isEqualTo(allowed ? 200 : 403);
            }
        }
    }

    @Test
    void wrong_password_does_not_start_a_session() throws Exception {
        AppUser user = accounts.create(RoleCode.MANAGER.name());

        mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password("не тот пароль"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/ui/login?error"));
    }

    /**
     * FR-11.1, UI-1: перебор пароля через форму приводит к блокировке.
     *
     * <p>Счётчик неудач наращивал только REST-вход, а форму обрабатывает стандартный провайдер, который
     * лишь читает признак блокировки. Через `/ui/login` пароль можно было подбирать без ограничений, а
     * ветка страницы «слишком много попыток» была недостижима.
     */
    @Test
    void repeated_wrong_passwords_lock_the_account_and_the_page_says_for_how_long() throws Exception {
        AppUser user = accounts.create(RoleCode.MANAGER.name());
        int allowed = 5;

        for (int attempt = 0; attempt < allowed; attempt++) {
            mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password("не тот пароль"))
                    .andExpect(unauthenticated());
        }

        // Учётная запись заблокирована: даже верный пароль теперь не пускает, а страница сообщает срок.
        mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrlPattern("/ui/login?error&locked&minutes=*"));
    }

    @Test
    void anonymous_visitor_is_sent_to_the_login_page() throws Exception {
        mockMvc.perform(get("/ui/subjects"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/ui/login"));
    }

    @Test
    void assets_are_served_from_the_artifact_without_authentication() throws Exception {
        // UI-0.2: ассеты в поставке, без CDN — иначе интерфейс не откроется в закрытом контуре.
        mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css")).andExpect(status().isOk());
        mockMvc.perform(get("/webjars/htmx.org/dist/htmx.min.js")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/css/inconsensu.css")).andExpect(status().isOk());
    }
}
