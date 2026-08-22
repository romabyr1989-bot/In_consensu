package ru.example.inconsensu.ui;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
    void employee_of_every_role_signs_in_and_lands_in_the_workplace() throws Exception {
        for (RoleCode role : List.of(
                RoleCode.ADMIN,
                RoleCode.DPO,
                RoleCode.LAWYER,
                RoleCode.MANAGER,
                RoleCode.MARKETING,
                RoleCode.AUDITOR)) {
            // Прежние экраны удалены (ADR-0089): сохранённая ссылка на /ui/ уводит в рабочее место.
            mockMvc.perform(get("/ui/").session(loginAs(role.name())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/app/"));
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
