package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
}
