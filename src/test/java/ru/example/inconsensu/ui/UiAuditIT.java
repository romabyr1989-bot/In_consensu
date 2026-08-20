package ru.example.inconsensu.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Приёмка UI-15: фильтры событий и журнала доступа к ПДн, колонка «кто». */
@AutoConfigureMockMvc
class UiAuditIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Test
    void events_tab_has_a_subject_filter_and_keeps_the_chosen_values() throws Exception {
        MockHttpSession auditor = loginAs(RoleCode.AUDITOR.name());
        UUID subjectId = UUID.randomUUID();

        mockMvc.perform(get("/ui/audit").session(auditor))
                .andExpect(status().isOk())
                // UI-15 называет фильтр «ID субъекта» — поля не было вовсе.
                .andExpect(content().string(containsString("name=\"subjectId\"")))
                .andExpect(content().string(containsString("Сбросить фильтры")));

        mockMvc.perform(get("/ui/audit?aggregateType=consent&subjectId=" + subjectId + "&size=50")
                        .session(auditor))
                .andExpect(status().isOk())
                // UI-0.8: выбранные значения возвращаются в форму, иначе поля пусты, а таблица отфильтрована.
                .andExpect(content().string(containsString("value=\"consent\"")))
                .andExpect(content().string(containsString(subjectId.toString())))
                .andExpect(content().string(containsString("Ничего не найдено")));
    }

    @Test
    void access_log_names_the_employee_who_looked_at_the_data() throws Exception {
        AppUser manager = accounts.create(RoleCode.MANAGER.name());
        MockHttpSession managerSession = loginAs(manager);

        // Поиск клиента — операция с ПДн, она обязана оставить след с именем сотрудника (§7, UI-15).
        // Сам поиск выполняется на переходе по выданной ссылке, поэтому редирект нужно пройти до конца.
        String redirect = mockMvc.perform(post("/ui/subjects/search")
                        .session(managerSession)
                        .with(csrf())
                        .param("query", "Травин"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        mockMvc.perform(get(redirect).session(managerSession)).andExpect(status().isOk());

        mockMvc.perform(get("/ui/audit/access-log").session(loginAs(RoleCode.AUDITOR.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(manager.getFullName())))
                // Раньше в колонке «кто» стоял идентификатор, а при входе через форму — пусто.
                .andExpect(content().string(not(containsString(manager.getId().toString()))));
    }

    @Test
    void access_log_filters_are_kept_and_can_be_reset() throws Exception {
        MockHttpSession auditor = loginAs(RoleCode.AUDITOR.name());

        mockMvc.perform(get("/ui/audit/access-log?endpoint=/ui/subjects").session(auditor))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"/ui/subjects\"")))
                .andExpect(content().string(containsString("Сбросить фильтры")));
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
}
