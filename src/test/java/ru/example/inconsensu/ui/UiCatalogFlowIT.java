package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * Приёмка UI-7 … UI-10: конструктор с панелью проверки реквизитов, согласование двумя ролями и публикация.
 *
 * <p>Сценарий проходится так же, как это делает сотрудник: страницами и формами, а не вызовами сервисов.
 */
@AutoConfigureMockMvc
class UiCatalogFlowIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private OperatorSettingsService settings;

    @Autowired
    private ConsentFormService forms;

    @BeforeEach
    void fillOperatorRequisites() {
        // FR-1.3: без реквизитов оператора форма не пройдёт проверку — заполняем их, как это сделал бы админ.
        settings.update(Map.of(
                "operator.name", "ООО «Тестовый оператор»",
                "operator.address", "123001, Москва, ул. Тестовая, д. 1"));
    }

    @Test
    void draft_goes_through_the_builder_review_and_publication() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        String code = "UI_FLOW_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String redirect = mockMvc.perform(post("/ui/catalog/forms")
                        .session(lawyer)
                        .with(csrf())
                        .param("code", code)
                        .param("title", "Форма из конструктора"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        UUID formId = UUID.fromString(redirect.replaceAll(".*/forms/([0-9a-f-]{36})/edit", "$1"));

        // UI-8: панель проверки реквизитов показывает блокирующие нарушения пустого черновика.
        mockMvc.perform(get("/ui/catalog/forms/" + formId + "/edit").session(lawyer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Проверка реквизитов (ч. 4 ст. 9 152-ФЗ)")))
                .andExpect(content().string(containsString("Блокирующие нарушения")))
                .andExpect(content().string(containsString("Форма не содержит ни одного пункта")));

        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/edit")
                        .session(lawyer)
                        .with(csrf())
                        .param("title", "Форма из конструктора")
                        .param(
                                "body",
                                "Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}} "
                                        + "({{operator.address}}) на обработку персональных данных.")
                        .param("processingActions", "сбор, запись, хранение, уничтожение")
                        .param("revocationProcedure", "действует до отзыва; отзыв — в личном кабинете")
                        .param("sourceChannels", "WEBSITE_APPLICATION")
                        .param("items[0].typeCode", "PDN_PROCESSING")
                        .param("items[0].text", "Согласие на обработку персональных данных")
                        .param("items[0].purposes", "рассмотрение заявки")
                        .param("items[0].categories", "FIO")
                        .param("items[0].categories", "PHONE")
                        .param("items[0].mandatory", "true"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/ui/catalog/forms/" + formId + "/edit").session(lawyer))
                .andExpect(content().string(containsString("Черновик сохранён")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Блокирующие нарушения"))));

        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/submit")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/approve")
                        .session(lawyer)
                        .with(csrf())
                        .param("comment", "проверено юристом"))
                .andExpect(status().is3xxRedirection());

        // UI-9: публикация доступна DPO и только после одобрения обеими ролями.
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/publish")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(forms.get(formId).getStatus())
                .as("одобрение только юристом не должно публиковать форму")
                .isEqualTo(FormStatus.ON_REVIEW);

        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/approve")
                        .session(dpo)
                        .with(csrf())
                        .param("comment", "проверено DPO"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/publish")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.PUBLISHED);

        // UI-10: опубликованная версия показывает контрольную сумму и список версий.
        mockMvc.perform(get("/ui/catalog/forms/" + formId).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Контрольная сумма")))
                .andExpect(content().string(containsString("sha256:")));
    }

    @Test
    void review_screen_is_closed_for_a_role_without_the_right_to_approve() throws Exception {
        MockHttpSession marketing = loginAs(RoleCode.MARKETING.name());

        mockMvc.perform(post("/ui/catalog/forms")
                        .session(marketing)
                        .with(csrf())
                        .param("code", "UI_DENIED")
                        .param("title", "Попытка без прав"))
                .andExpect(status().isForbidden());
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
