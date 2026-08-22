package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * Приёмка UI-6 … UI-10 на слое рабочего места: конструктор формы, согласование двумя ролями и публикация.
 *
 * <p>Проверяется правило сервера, а не разметка: прежний сценарий ходил по страницам Thymeleaf, но кворум
 * согласующих, право на публикацию и проверка реквизитов ч. 4 ст. 9 152-ФЗ живут в приложении и от смены
 * интерфейса не изменились. Прятать кнопку недостаточно — операцию обязан запрещать сервер.
 */
@AutoConfigureMockMvc
class WorkplaceCatalogApiIT extends AbstractIntegrationTest {

    private static final String CATALOG = "/ui/api/catalog";

    /** Черновик, собранный по ч. 4 ст. 9 152-ФЗ: с ним форма проходит проверку реквизитов. */
    private static final String VALID_DRAFT =
            """
            {"title":"%s",
             "body":"Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}} \
            ({{operator.address}}) на обработку персональных данных.",
             "processingActions":"сбор, запись, хранение, уничтожение",
             "revocationProcedure":"действует до отзыва; отзыв — в личном кабинете",
             "sourceChannels":["WEBSITE_APPLICATION"],
             "items":[{"typeCode":"PDN_PROCESSING","text":"Согласие на обработку персональных данных",
                       "purposes":["рассмотрение заявки"],"categories":["FIO","PHONE"],"mandatory":true}]}
            """;

    /** Тот же черновик без обязательных реквизитов: нет цели, действий, порядка отзыва и блока идентификации. */
    private static final String INCOMPLETE_DRAFT =
            """
            {"title":"Форма без реквизитов",
             "body":"Даю согласие {{operator.name}} на обработку персональных данных.",
             "processingActions":"",
             "revocationProcedure":"",
             "sourceChannels":["WEBSITE_APPLICATION"],
             "items":[{"typeCode":"PDN_PROCESSING","text":"Согласие на обработку персональных данных",
                       "purposes":[],"categories":["FIO"],"mandatory":true}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentFormService forms;

    @Autowired
    private ConsentTypeService types;

    @BeforeEach
    void fillOperatorRequisites() {
        // FR-1.3: без наименования и адреса оператора любая форма считается неполной.
        testForms.fillOperatorRequisites();
    }

    /**
     * UI-8 … UI-10: путь формы от черновика до версии, под которой можно давать согласия.
     *
     * <p>Публикация до кворума проверяется здесь же: одобрения одного юриста мало, и отказ обязан быть
     * отказом сервера, а не отсутствием кнопки на экране.
     */
    @Test
    void draft_is_published_only_after_both_required_roles_approve() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        String title = "Форма из конструктора " + UUID.randomUUID().toString().substring(0, 8);
        UUID formId = createDraft(lawyer, title);

        // UI-8: пустой черновик сразу назван неполным — автор видит нарушения до отправки.
        mockMvc.perform(get(CATALOG + "/forms/" + formId).session(lawyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.editable").value(true));

        mockMvc.perform(post(CATALOG + "/forms/" + formId)
                        .session(lawyer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DRAFT.formatted(title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.violations").isEmpty());

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/submit")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_REVIEW"));

        approve(lawyer, formId, "проверено юристом");

        // FR-2.1: одобрения одной роли недостаточно — форма остаётся на согласовании.
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/publish")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:conflict"));
        assertThat(forms.get(formId).getStatus())
                .as("одобрение только юристом не должно публиковать форму")
                .isEqualTo(FormStatus.ON_REVIEW);

        approve(dpo, formId, "проверено DPO");
        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.APPROVED);

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/publish")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                // FR-1.5, FR-1.6: опубликованная версия неизменяема и имеет контрольную сумму текста.
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.checksum").value(Matchers.startsWith("sha256:")))
                .andExpect(jsonPath("$.issuedConsents").value(0));

        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.PUBLISHED);
    }

    /**
     * FR-1.3: форма без обязательных реквизитов не уходит на согласование.
     *
     * <p>Нарушения возвращаются все сразу и с кодами: юрист правит форму за один заход, а не узнаёт о
     * проблемах по одной.
     */
    @Test
    void form_that_breaks_the_requisites_of_the_law_does_not_go_to_review() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        UUID formId = createDraft(lawyer, "Форма без реквизитов");

        String saved = mockMvc.perform(post(CATALOG + "/forms/" + formId)
                        .session(lawyer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INCOMPLETE_DRAFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(codesOf(saved, "violations"))
                .contains(
                        "processing-actions-missing",
                        "revocation-procedure-missing",
                        "subject-identification-missing",
                        "purpose-missing");

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/submit")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(Matchers.greaterThan(0)));

        assertThat(forms.get(formId).getStatus())
                .as("форма с нарушениями обязана остаться черновиком")
                .isEqualTo(FormStatus.DRAFT);
    }

    /**
     * Приложение E: юрист готовит текст, но выпускает версию ответственный за ПДн или администратор.
     *
     * <p>Право DPO на публикацию проверяет сквозной сценарий, здесь — отказ юристу и допуск администратора.
     */
    @Test
    void publication_is_closed_for_the_lawyer_and_open_to_the_administrator() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        UUID formId = formOnReview(lawyer, "Форма о правах на публикацию");
        approve(lawyer, formId, "проверено юристом");
        approve(loginAs(RoleCode.DPO.name()), formId, "проверено DPO");

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/publish")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        assertThat(forms.get(formId).getStatus())
                .as("отказ в правах не должен менять состояние формы")
                .isEqualTo(FormStatus.APPROVED);

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/publish")
                        .session(loginAs(RoleCode.ADMIN.name()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    /** Приложение E: маркетинг работает с каталогом только на чтение — ни завести форму, ни одобрить её. */
    @Test
    void review_is_closed_for_a_role_without_the_right_to_approve() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        UUID formId = formOnReview(lawyer, "Форма без прав у маркетинга");
        MockHttpSession marketing = loginAs(RoleCode.MARKETING.name());

        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/approve")
                        .session(marketing)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"согласовано маркетингом\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post(CATALOG + "/forms")
                        .session(marketing)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"UI_API_DENIED\",\"title\":\"Попытка без прав\"}"))
                .andExpect(status().isForbidden());

        // Каталог маркетингу виден, но своих дел в нём нет: решение принимают юрист и ответственный за ПДн.
        assertThat(awaitingDecisionOf(marketing)).isEmpty();
        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.ON_REVIEW);
    }

    /**
     * UI-6, FR-1.1: тип согласия правится по коду, и правка не трогает порядок сортировки.
     *
     * <p>Порядок приходит из формы: если подставить в него ноль, правка перемешает список типов в
     * конструкторе, а вместе с ним и порядок пунктов формы.
     */
    @Test
    void consent_type_edited_from_the_workplace_keeps_its_sort_order() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String code =
                "UI_API_TYPE_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        mockMvc.perform(post(CATALOG + "/types")
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(typeRequest(code, "До правки", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.sortOrder").value(777));

        mockMvc.perform(post(CATALOG + "/types")
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(typeRequest(code, "После правки", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameRu").value("После правки"))
                .andExpect(jsonPath("$.sortOrder").value(777));

        assertThat(types.getByCode(code).getNameRu()).isEqualTo("После правки");
        assertThat(types.getByCode(code).getSortOrder())
                .as("порядок сортировки не должен обнуляться при правке")
                .isEqualTo(777);
    }

    /**
     * UI-7: «ждут вашего решения» — это дела самого согласующего.
     *
     * <p>Список отдавался общим, по всем формам на согласовании: юрист, уже одобривший форму, продолжал
     * видеть её среди своих дел, хотя решать по ней ему больше нечего.
     */
    @Test
    void approved_form_leaves_the_lawyers_own_queue_but_stays_on_review() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        UUID formId = formOnReview(
                lawyer, "Форма очереди " + UUID.randomUUID().toString().substring(0, 8));

        assertThat(awaitingDecisionOf(lawyer))
                .as("форма только что отправлена на согласование и ждёт решения юриста")
                .contains(formId.toString());

        approve(lawyer, formId, "проверено юристом");

        assertThat(awaitingDecisionOf(lawyer))
                .as("одобренную форму юрист не должен видеть среди ждущих его решения")
                .doesNotContain(formId.toString());
        assertThat(awaitingDecisionOf(loginAs(RoleCode.DPO.name())))
                .as("решения ответственного за ПДн по форме ещё нет")
                .contains(formId.toString());

        mockMvc.perform(get(CATALOG + "/forms/" + formId).session(lawyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_REVIEW"));
        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.ON_REVIEW);
    }

    /** Без токена запрос не проходит: иначе чужая страница отправила бы форму на согласование за юриста. */
    @Test
    void request_without_a_csrf_token_does_not_move_the_form() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());
        UUID formId = createDraft(lawyer, "Черновик без токена");

        // Ответ — код, а не страница входа: приложению нужно отличать истёкшую сессию от успеха.
        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/submit").session(lawyer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"));

        assertThat(forms.get(formId).getStatus()).isEqualTo(FormStatus.DRAFT);
    }

    /** Черновик, доведённый до состояния «на согласовании»: то же, что делает юрист в конструкторе. */
    private UUID formOnReview(MockHttpSession lawyer, String title) throws Exception {
        UUID formId = createDraft(lawyer, title);
        mockMvc.perform(post(CATALOG + "/forms/" + formId)
                        .session(lawyer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DRAFT.formatted(title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/submit")
                        .session(lawyer)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_REVIEW"));
        return formId;
    }

    private UUID createDraft(MockHttpSession session, String title) throws Exception {
        String code = "UI_API_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String created = mockMvc.perform(post(CATALOG + "/forms")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"title\":\"%s\"}".formatted(code, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(new ObjectMapper().readTree(created).get("id").asText());
    }

    private void approve(MockHttpSession session, UUID formId, String comment) throws Exception {
        mockMvc.perform(post(CATALOG + "/forms/" + formId + "/approve")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"%s\"}".formatted(comment)))
                .andExpect(status().isOk());
    }

    private static String typeRequest(String code, String nameRu, boolean update) {
        String template =
                """
                {"code":"%s","nameRu":"%s","description":"Тип согласия для приёмочного теста",
                 "category":"OTHER","channels":[],"requiresThirdParty":false,"businessSignificant":false,
                 "sortOrder":777,"update":%s}
                """;
        return template.formatted(code, nameRu, update);
    }

    /** Идентификаторы форм из блока «ждут решения»: он считается по ролям того, кто спрашивает. */
    private List<String> awaitingDecisionOf(MockHttpSession session) throws Exception {
        String page = mockMvc.perform(get(CATALOG + "/forms").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> ids = new ArrayList<>();
        for (JsonNode row : new ObjectMapper().readTree(page).get("awaitingDecision")) {
            ids.add(row.get("id").asText());
        }
        return ids;
    }

    /** Разбор ответа: фильтры JsonPath по значению поля здесь не работают, а порядок нарушений не задан. */
    private static List<String> codesOf(String json, String array) throws Exception {
        List<String> codes = new ArrayList<>();
        for (JsonNode finding : new ObjectMapper().readTree(json).get(array)) {
            codes.add(finding.get("code").asText());
        }
        return codes;
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
