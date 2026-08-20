package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;

/** Приёмка UI-13: вкладки «Правила» и «Журнал», фильтры журнала и просмотр текста письма. */
@AutoConfigureMockMvc
class UiNotificationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private ConsentTypeService types;

    @Test
    void rules_tab_shows_filters_of_a_rule_and_the_form_can_set_them() throws Exception {
        MockHttpSession dpo = loginAs();
        var type = types.activeTypes().getFirst();

        mockMvc.perform(post("/ui/notifications/rules")
                        .session(dpo)
                        .with(csrf())
                        .param(
                                "name",
                                "Правило по типу "
                                        + UUID.randomUUID().toString().substring(0, 6))
                        .param("triggerType", NotificationTrigger.EXPIRING.name())
                        .param("daysBefore", "30, 7")
                        .param("recipientRoles", RoleCode.DPO.name())
                        .param("channels", NotificationChannel.EMAIL.name())
                        .param("consentTypeId", type.getId().toString()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/ui/notifications").session(dpo))
                .andExpect(status().isOk())
                // UI-13: в таблице правил есть колонка фильтров, а роли названы по-русски.
                .andExpect(content().string(containsString("Фильтры")))
                .andExpect(content().string(containsString("тип: " + type.getNameRu())))
                .andExpect(content().string(containsString("Ответственный за ПДн")))
                .andExpect(content().string(not(containsString("<td>DPO</td>"))));

        assertThat(rules.list().stream().map(NotificationRule::getConsentTypeId))
                .as("фильтр правила должен сохраняться, а не подменяться на null")
                .contains(type.getId());
    }

    @Test
    void journal_tab_filters_by_status_and_opens_the_message_text() throws Exception {
        MockHttpSession dpo = loginAs();
        String recipient = "dpo-journal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
        String subjectLine =
                "In consensu: проверка журнала " + UUID.randomUUID().toString().substring(0, 6);
        enqueue(recipient, subjectLine);

        String journal = mockMvc.perform(get("/ui/notifications?tab=journal").session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Сбросить фильтры")))
                .andExpect(content().string(containsString(subjectLine)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Фильтр по статусу «отправлено» не должен показывать письмо из очереди.
        mockMvc.perform(get("/ui/notifications?tab=journal&status=SENT").session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(subjectLine))));

        String link = journal.substring(journal.indexOf("/ui/notifications/"));
        String messageId = link.substring("/ui/notifications/".length(), link.indexOf('"'));
        mockMvc.perform(get("/ui/notifications/" + messageId).session(dpo))
                .andExpect(status().isOk())
                // UI-13: просмотр текста письма — что именно ушло получателю.
                .andExpect(content().string(containsString("Текст уведомления")))
                .andExpect(content().string(containsString(recipient)))
                .andExpect(content().string(containsString("Срок согласия заканчивается")));
    }

    @Test
    void journal_filter_by_date_narrows_the_result() throws Exception {
        MockHttpSession dpo = loginAs();
        String subjectLine = "In consensu: дата " + UUID.randomUUID().toString().substring(0, 6);
        enqueue("dpo-date-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru", subjectLine);

        // Верхняя граница — вчерашний день: сегодняшнее письмо в неё попасть не должно.
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        mockMvc.perform(get("/ui/notifications?tab=journal&to=" + yesterday).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(subjectLine))));

        mockMvc.perform(get("/ui/notifications?tab=journal&from=" + yesterday).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(subjectLine)));
    }

    private void enqueue(String recipient, String subjectLine) {
        NotificationRule rule = RunAs.roles(
                "test-admin",
                List.of("ADMIN"),
                () -> rules.create(new NotificationRuleService.RuleForm(
                        "Журнал " + UUID.randomUUID().toString().substring(0, 8),
                        NotificationTrigger.EXPIRING,
                        List.of(30),
                        null,
                        null,
                        Set.of(recipient),
                        Set.of(),
                        Set.of(NotificationChannel.EMAIL),
                        true)));
        notifications.enqueue(
                rule.getId(),
                null,
                null,
                "ui-journal-" + UUID.randomUUID(),
                NotificationChannel.EMAIL,
                recipient,
                subjectLine,
                "Срок согласия заканчивается через 30 дней.",
                Map.of());
    }

    private MockHttpSession loginAs() throws Exception {
        AppUser user = accounts.create(RoleCode.DPO.name());
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
