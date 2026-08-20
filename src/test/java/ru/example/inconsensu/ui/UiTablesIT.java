package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Приёмка UI-0.8 и UI-0.6: таблицы сортируются по колонкам, размер страницы выбирается, пустые состояния
 * объясняют, что изменить.
 */
@AutoConfigureMockMvc
class UiTablesIT extends AbstractIntegrationTest {

    /** Экраны со списками и роль, которой они открыты (§16.2). */
    private static final List<String[]> TABLES = List.of(
            new String[] {"/ui/catalog/types", RoleCode.ADMIN.name()},
            new String[] {"/ui/catalog/forms", RoleCode.ADMIN.name()},
            new String[] {"/ui/third-parties", RoleCode.ADMIN.name()},
            new String[] {"/ui/admin/users", RoleCode.ADMIN.name()},
            new String[] {"/ui/import", RoleCode.ADMIN.name()},
            new String[] {"/ui/audit", RoleCode.AUDITOR.name()},
            new String[] {"/ui/audit/access-log", RoleCode.AUDITOR.name()},
            new String[] {"/ui/notifications?tab=journal", RoleCode.DPO.name()});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Test
    void every_table_offers_sorting_by_columns() throws Exception {
        for (String[] table : TABLES) {
            String body = mockMvc.perform(get(table[0]).session(loginAs(table[1])))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body)
                    .as("на экране %s заголовки таблицы должны быть ссылками сортировки", table[0])
                    .contains("sort=")
                    .contains("direction=");
        }
    }

    @Test
    void sorting_keeps_the_chosen_filters_in_the_link() throws Exception {
        // Ссылка сортировки обязана нести уже выбранный фильтр: иначе она сбрасывала бы выборку.
        mockMvc.perform(get("/ui/catalog/forms?status=PUBLISHED").session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status=PUBLISHED&amp;sort=")));
    }

    @Test
    void empty_tables_explain_what_to_change() throws Exception {
        mockMvc.perform(get("/ui/catalog/types?category=DISTRIBUTION&active=false")
                        .session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ничего не найдено")));

        mockMvc.perform(get("/ui/import").session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Импорт ещё не запускался")));
    }

    @Test
    void settings_are_grouped_and_named_in_russian() throws Exception {
        mockMvc.perform(get("/ui/admin/settings").session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                // UI-16 перечисляет настройки группами, а не списком технических ключей.
                .andExpect(content().string(containsString("Реквизиты оператора")))
                .andExpect(content().string(containsString("Порог «заканчивается через N дней»")))
                .andExpect(content().string(containsString("Срок жизни ссылки")))
                .andExpect(content().string(containsString("Режим аутентификации")))
                .andExpect(content().string(containsString("Основной цвет")));
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
