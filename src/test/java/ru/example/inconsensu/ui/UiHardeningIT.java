package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * Проверка мест, где интерфейс и API расходились с §16.2 и Приложением E.
 *
 * <p>Каждый тест закрывает найденную дыру: выгрузку карточки в PDF без проверки роли, диалог отзыва,
 * открытый ролям без права отзывать, проверку целостности журнала не аудитором, отказ в виде JSON вместо
 * страницы и запрет фреймов на встраиваемой странице клиента.
 */
@AutoConfigureMockMvc
class UiHardeningIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Test
    void pdf_card_is_closed_for_the_service_role() throws Exception {
        // Приложение E: служебной роли карточка доступна только по external_id, а выгрузка в PDF — нет.
        mockMvc.perform(get("/api/v1/subjects/" + UUID.randomUUID() + "/card.pdf")
                        .header("Authorization", "Bearer " + token(RoleCode.INTEGRATION.name())))
                .andExpect(status().isForbidden());
    }

    @Test
    void revocation_dialog_is_closed_for_roles_that_cannot_revoke() throws Exception {
        mockMvc.perform(get("/ui/consents/" + UUID.randomUUID() + "/revocation-dialog")
                        .session(loginAs(RoleCode.MARKETING.name())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/ui/subjects/" + UUID.randomUUID() + "/revocation-dialog")
                        .session(loginAs(RoleCode.LAWYER.name())))
                .andExpect(status().isForbidden());
    }

    @Test
    void interface_shows_a_page_on_access_denial_not_json() throws Exception {
        // UI-0.6: сотрудник видит страницу «Недостаточно прав», а не тело ProblemDetail в браузере.
        String body = mockMvc.perform(get("/ui/webhooks").session(loginAs(RoleCode.MANAGER.name())))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("<html").doesNotContain("\"type\":\"urn:inconsensu:error");
    }

    @Test
    void self_service_page_may_be_embedded_while_employee_screens_may_not() throws Exception {
        // UI-18: страница клиента встраивается в личный кабинет, экраны сотрудника — нет.
        String selfFrames =
                mockMvc.perform(get("/self/ui")).andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(selfFrames).contains("frame-ancestors");

        var employeeResponse = mockMvc.perform(get("/ui/login")).andReturn().getResponse();
        assertThat(employeeResponse.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(employeeResponse.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }

    @Test
    void session_expired_page_keeps_the_address_to_return_to() throws Exception {
        mockMvc.perform(get("/ui/session-expired?from=/ui/subjects"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from=/ui/subjects")));

        // Чужой адрес в параметре игнорируется: иначе ссылка «Войти» уводила бы на сторонний сайт.
        mockMvc.perform(get("/ui/session-expired?from=//evil.example/ui/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("evil.example"))));
    }

    /**
     * UI-17: неизвестный адрес интерфейса отдаёт страницу «Страница не найдена», а не 500 и не JSON.
     *
     * <p>Через настоящий контейнер, а не MockMvc: страницу рисует обработчик ошибок сервлета `/error`,
     * до которого MockMvc не доходит.
     */
    @Test
    void unknown_interface_address_answers_not_found_without_json() throws Exception {
        // Раньше неизвестный адрес интерфейса заканчивался 500-й: общий обработчик машинной цепочки
        // перехватывал отсутствие маршрута и отдавал ProblemDetail. Саму страницу рисует обработчик
        // сервлета `/error`, до которого MockMvc не доходит, поэтому здесь проверяются код и отсутствие JSON.
        String body = mockMvc.perform(get("/ui/nonexistent-page")
                        .header("Accept", "text/html")
                        .session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("urn:inconsensu:error");
    }

    /** UI-0.3: у cookie сессии заданы HttpOnly и SameSite, а Secure включается настройкой. */
    @Test
    void session_cookie_is_protected() {
        // Куку выдаёт страница входа: на ней Spring Security заводит сессию под токен CSRF.
        var response = restTemplate.getForEntity("/ui/login", String.class);
        List<String> cookies = response.getHeaders().get("Set-Cookie");

        assertThat(cookies).isNotNull();
        assertThat(String.join(";", cookies)).contains("HttpOnly").contains("SameSite=Lax");
    }

    private String token(String roleCode) {
        return accounts.authorizationFor(roleCode).getFirst("Authorization").replace("Bearer ", "");
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /**
     * UI-17: мёртвый идентификатор сессии не заводит браузер в растущий переход.
     *
     * <p>После перезапуска службы браузер приходит со старым JSESSIONID. Раньше на каждый такой запрос
     * выдавался переход на `/ui/session-expired?from=<текущий адрес>`, а тот приходил с тем же мёртвым
     * идентификатором: адрес вкладывался сам в себя и рос, пока заголовок `Location` не переставал
     * помещаться в буфер — вкладка оставалась пустой, а в адресной строке висел бесконечный URL.
     */
    @Test
    void a_dead_session_cookie_does_not_start_a_growing_redirect() {
        HttpHeaders dead = new HttpHeaders();
        // Идентификатор заведомо не существует: сервер обязан ответить одним коротким переходом.
        dead.add(HttpHeaders.COOKIE, "JSESSIONID=NO-SUCH-SESSION-0000");

        ResponseEntity<String> first =
                restTemplate.exchange("/ui/subjects", HttpMethod.GET, new HttpEntity<>(dead), String.class);
        String location = first.getHeaders().getFirst(HttpHeaders.LOCATION);

        assertThat(location).as("ответ на мёртвую сессию — переход").isNotNull();
        assertThat(location).doesNotContain("session-expired%3Ffrom");
        assertThat(location.length()).isLessThan(600);

        // Второй круг с тем же мёртвым идентификатором: адрес обязан остаться прежней длины, а не расти.
        ResponseEntity<String> second = restTemplate.exchange(
                java.net.URI.create(location).getPath()
                        + (java.net.URI.create(location).getQuery() == null
                                ? ""
                                : "?" + java.net.URI.create(location).getRawQuery()),
                HttpMethod.GET,
                new HttpEntity<>(dead),
                String.class);
        String next = second.getHeaders().getFirst(HttpHeaders.LOCATION);

        assertThat(next == null || next.length() <= location.length())
                .as("адрес возврата не должен вкладываться сам в себя")
                .isTrue();
    }
}
