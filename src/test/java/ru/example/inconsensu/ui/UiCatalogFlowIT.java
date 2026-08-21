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
import ru.example.inconsensu.common.domain.ConsentCategory;
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

    @Autowired
    private ru.example.inconsensu.catalog.application.ConsentTypeService types;

    @BeforeEach
    void fillOperatorRequisites() {
        // FR-1.3: без реквизитов оператора форма не пройдёт проверку — заполняем их, как это сделал бы админ.
        settings.update(Map.of(
                "operator.name", "ООО «Тестовый оператор»",
                "operator.address", "123001, Москва, ул. Тестовая, д. 1"));
    }

    @Test
    void draft_goes_through_the_builder_review_and_publication() throws Exception {
        AppUser lawyerUser = accounts.create(RoleCode.LAWYER.name());
        MockHttpSession lawyer = loginAs(lawyerUser);
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

        // UI-9: панель решений называет роль по-русски, автора решения и момент, а не один код роли.
        mockMvc.perform(get("/ui/catalog/forms/" + formId + "/review").session(lawyer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Юрист")))
                .andExpect(content().string(containsString(lawyerUser.getFullName())))
                .andExpect(content().string(containsString("проверено юристом")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">LAWYER<"))))
                // UI-9: третье лицо у пункта названо явно, даже когда его нет.
                .andExpect(content().string(containsString("Третье лицо: <span>не требуется")))
                // UI-0.4: категории ПДн и срок — по-русски, а не кодами справочника и периодом ISO.
                .andExpect(content().string(containsString("Срок: <span>до отзыва")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">FIO<"))));

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

        // UI-10: контрольная сумма, реквизиты, даты и авторы, счётчик выданных согласий.
        mockMvc.perform(get("/ui/catalog/forms/" + formId).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Контрольная сумма")))
                .andExpect(content().string(containsString("sha256:")))
                .andExpect(content().string(containsString("Проверка реквизитов (ч. 4 ст. 9 152-ФЗ)")))
                .andExpect(content().string(containsString("Даты и авторы")))
                .andExpect(content().string(containsString("Опубликована")))
                .andExpect(content().string(containsString("Выдано согласий по этой версии")))
                .andExpect(content().string(containsString(lawyerUser.getLogin())));
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
        return loginAs(accounts.create(roleCode));
    }

    private MockHttpSession loginAs(AppUser user) throws Exception {
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /**
     * UI-6: тип согласия правится из интерфейса, код при этом неизменяем (FR-1.1).
     *
     * <p>Форма редактирования была недоступна: серверная часть уже принимала `update=true`, но ни одна
     * страница этот признак не отправляла. Заодно проверяется порядок сортировки: он приходит из формы, а
     * раньше в него жёстко подставлялся ноль, и правка перемешала бы список типов в конструкторе.
     */
    @Test
    void consent_type_can_be_edited_from_the_interface_without_losing_its_order() throws Exception {
        MockHttpSession session = loginAs(RoleCode.DPO.name());
        String code = "UI_EDIT_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        mockMvc.perform(post("/ui/catalog/types")
                        .session(session)
                        .with(csrf())
                        .param("code", code)
                        .param("nameRu", "До правки")
                        .param("category", ConsentCategory.OTHER.name())
                        .param("sortOrder", "777"))
                .andExpect(status().is3xxRedirection());

        // Форма правки открывается по ссылке из таблицы и приходит заполненной.
        mockMvc.perform(get("/ui/catalog/types").param("edit", code).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Изменить тип согласия: " + code)))
                .andExpect(content().string(containsString("До правки")));

        mockMvc.perform(post("/ui/catalog/types")
                        .session(session)
                        .with(csrf())
                        .param("code", code)
                        .param("nameRu", "После правки")
                        .param("category", ConsentCategory.OTHER.name())
                        .param("update", "true")
                        .param("sortOrder", "777"))
                .andExpect(status().is3xxRedirection());

        assertThat(types.getByCode(code).getNameRu()).isEqualTo("После правки");
        assertThat(types.getByCode(code).getSortOrder())
                .as("порядок сортировки не должен обнуляться при правке")
                .isEqualTo(777);
    }

    /**
     * UI-2, UI-7: «ждут вашего решения» — это дела самого согласующего.
     *
     * <p>И плитка дашборда, и блок на списке форм показывали все формы на согласовании, а счётчик в меню —
     * только личные: юрист, уже одобривший форму, видел её среди своих дел и три разных числа на трёх
     * экранах.
     */
    @Test
    void approved_form_leaves_the_lawyers_own_queue_but_stays_on_review() throws Exception {
        AppUser lawyerUser = accounts.create(RoleCode.LAWYER.name());
        MockHttpSession lawyer = loginAs(lawyerUser);
        String title = "Форма очереди " + UUID.randomUUID().toString().substring(0, 8);
        UUID formId = submitForReview(lawyer, title);

        assertThat(ownQueueOf(lawyer))
                .as("форма только что отправлена на согласование и ждёт решения юриста")
                .contains(title);

        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/approve")
                        .session(lawyer)
                        .with(csrf())
                        .param("comment", "проверено юристом"))
                .andExpect(status().is3xxRedirection());

        // Юрист своё решение принял: форма ушла из его очереди, но остаётся на согласовании у DPO.
        assertThat(ownQueueOf(lawyer))
                .as("одобренную форму юрист не должен видеть среди ждущих его решения")
                .doesNotContain(title);
        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.ON_REVIEW);
        assertThat(ownQueueOf(loginAs(RoleCode.DPO.name())))
                .as("решения DPO по форме ещё нет")
                .contains(title);
    }

    /**
     * Содержимое блока «ждут решения» на списке форм.
     *
     * <p>Только блок, а не вся страница: форма на согласовании стоит и в общей таблице, поэтому поиск по
     * всему ответу ничего не доказывал бы.
     */
    private String ownQueueOf(MockHttpSession session) throws Exception {
        String page = mockMvc.perform(get("/ui/catalog/forms").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int header = page.indexOf("На согласовании — ждут решения");
        if (header < 0) {
            return "";
        }
        int table = page.indexOf("table-responsive", header);
        return page.substring(header, table < 0 ? page.length() : table);
    }

    /** Черновик, доведённый до состояния «на согласовании»: то же, что делает юрист в конструкторе. */
    private UUID submitForReview(MockHttpSession lawyer, String title) throws Exception {
        String code = "UI_QUEUE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String redirect = mockMvc.perform(post("/ui/catalog/forms")
                        .session(lawyer)
                        .with(csrf())
                        .param("code", code)
                        .param("title", title))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        UUID formId = UUID.fromString(redirect.replaceAll(".*/forms/([0-9a-f-]{36})/edit", "$1"));
        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/edit")
                        .session(lawyer)
                        .with(csrf())
                        .param("title", title)
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
        mockMvc.perform(post("/ui/catalog/forms/" + formId + "/submit")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        return formId;
    }
}
