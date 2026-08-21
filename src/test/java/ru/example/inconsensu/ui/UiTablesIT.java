package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            new String[] {"/ui/notifications?tab=journal", RoleCode.DPO.name()},
            new String[] {"/ui/notifications", RoleCode.DPO.name()},
            new String[] {"/ui/webhooks", RoleCode.ADMIN.name()});
    // Список клиентов проверяется в UiRevocationIT: его таблица появляется только при найденных строках,
    // а клиента для этого нужно сначала завести.

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
    void sorting_keeps_the_chosen_filters_and_page_size() throws Exception {
        // Ссылка сортировки обязана нести и фильтр, и размер страницы: иначе она сбрасывает выборку.
        mockMvc.perform(get("/ui/catalog/forms?status=PUBLISHED&size=50").session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status=PUBLISHED")))
                .andExpect(content().string(containsString("size=50")))
                .andExpect(content().string(containsString("sort=")));
    }

    /** UI-0.8: переход по страницам не должен терять сортировку, размер страницы и фильтры. */
    @Test
    void pagination_keeps_sorting_and_filters() throws Exception {
        String body = mockMvc.perform(get("/ui/audit?aggregateType=consent&size=20&sort=occurredAt&direction=desc")
                        .session(loginAs(RoleCode.AUDITOR.name())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Пагинация показывается только при нескольких страницах, поэтому проверяется форма выбора размера:
        // она обязана сохранять и сортировку, и фильтр.
        assertThat(body).contains("name=\"sort\"").contains("value=\"occurredAt\"");
        assertThat(body).contains("name=\"aggregateType\"");
    }

    @Test
    void empty_tables_explain_what_to_change() throws Exception {
        mockMvc.perform(get("/ui/catalog/types?category=DISTRIBUTION&active=false")
                        .session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ничего не найдено")));

        // Журнал уведомлений за давно прошедший день пуст независимо от того, что успели создать соседние
        // тесты: проверка не должна зависеть от порядка классов в прогоне.
        mockMvc.perform(get("/ui/notifications?tab=journal&from=2000-01-01&to=2000-01-02")
                        .session(loginAs(RoleCode.DPO.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Уведомлений не найдено")));
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

    /** UI-0.1: операции §9, которые раньше были только в API, доступны и с экранов. */
    @Test
    void interface_covers_editing_of_rules_subscriptions_and_clients() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        MockHttpSession admin = loginAs(RoleCode.ADMIN.name());

        // Правило заводится через тот же экран: кнопка «Изменить» появляется в строке правила.
        mockMvc.perform(post("/ui/notifications/rules")
                        .session(dpo)
                        .with(csrf())
                        .param(
                                "name",
                                "Правка "
                                        + java.util.UUID.randomUUID().toString().substring(0, 6))
                        .param("triggerType", "EXPIRING")
                        .param("daysBefore", "30")
                        .param("recipientRoles", RoleCode.DPO.name())
                        .param("channels", "EMAIL"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/ui/notifications").session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Изменить")));

        mockMvc.perform(post("/ui/webhooks")
                        .session(admin)
                        .with(csrf())
                        .param(
                                "name",
                                "CRM " + java.util.UUID.randomUUID().toString().substring(0, 6))
                        .param("url", "https://crm.example.ru/hooks/cus"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/ui/webhooks").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Изменить")));

        mockMvc.perform(get("/ui/subjects").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Завести или обновить клиента")));
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
