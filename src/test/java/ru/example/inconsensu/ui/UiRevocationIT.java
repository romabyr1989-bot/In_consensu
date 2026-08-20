package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * Приёмка UI-4 и UI-5: сценарий «канал разрешён → отзыв через интерфейс → канал запрещён».
 *
 * <p>Проверка идёт по HTML, который реально отдаёт сервер: именно так сотрудник видит карточку, и
 * расхождение между расчётом каналов и тем, что нарисовано на плитках, поймается здесь.
 */
@AutoConfigureMockMvc
class UiRevocationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private ru.example.inconsensu.audit.infrastructure.PdnAccessLogRepository accessLog;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ru.example.inconsensu.registry.application.ConsentQueryService consents;

    @Autowired
    private ru.example.inconsensu.registry.application.SubjectService subjects;

    @Test
    void revocation_through_the_interface_closes_the_channel_immediately() throws Exception {
        Consent consent = registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());
        UUID subjectId = consent.getSubjectId();

        mockMvc.perform(get("/ui/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Электронная почта")))
                .andExpect(content().string(containsString("можно")));

        mockMvc.perform(get("/ui/consents/" + consent.getId() + "/revocation-dialog")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отзыв необратим и вступает в силу немедленно")));

        String afterRevoke = mockMvc.perform(post("/ui/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .with(csrf())
                        // Заголовок htmx: так запрос приходит из диалога. Без него сервер отвечает
                        // обычным переходом — форма работает и со отключёнными скриптами.
                        .header("HX-Request", "true")
                        .param("reason", "клиент попросил прекратить рассылку")
                        .param("revocationSource", "CALL_CENTER")
                        .param(
                                "caseNumber",
                                "ОБР-UI-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(afterRevoke).contains("Согласие отозвано");
        // FR-8.3: плитка канала обязана погаснуть в том же ответе, без повторного захода в карточку.
        assertThat(afterRevoke).contains("Реклама по email запрещена: согласие отозвано");
    }

    @Test
    void revocation_is_closed_for_a_role_without_the_right() throws Exception {
        Consent consent = registerAdvertisingConsent();

        mockMvc.perform(post("/ui/consents/" + consent.getId() + "/revoke")
                        .session(loginAs(RoleCode.MARKETING.name()))
                        .with(csrf())
                        .param("reason", "попытка без прав")
                        .param("revocationSource", "CALL_CENTER")
                        .param("caseNumber", "ОБР-UI-403"))
                .andExpect(status().isForbidden());
    }

    @Test
    void request_without_csrf_token_changes_nothing() throws Exception {
        Consent consent = registerAdvertisingConsent();

        // UI-0.3: формы интерфейса защищены CSRF, иначе отзыв можно было бы вызвать со стороннего сайта.
        int status = mockMvc.perform(post("/ui/consents/" + consent.getId() + "/revoke")
                        .session(loginAs(RoleCode.ADMIN.name()))
                        .param("reason", "без токена")
                        .param("revocationSource", "CALL_CENTER")
                        .param("caseNumber", "ОБР-UI-CSRF"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).as("запрос без токена не должен выполняться").isNotEqualTo(200);
        assertThat(consents.get(consent.getId()).consent().getRevokedAt())
                .as("согласие обязано остаться действующим")
                .isNull();
    }

    @Test
    void contact_is_masked_until_the_employee_asks_to_reveal_it() throws Exception {
        Consent consent = registerAdvertisingConsent();

        mockMvc.perform(get("/ui/subjects/" + consent.getSubjectId()).session(loginAs(RoleCode.MARKETING.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("t***@example.ru")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("travin-ui"))));
    }

    @Test
    void search_page_finds_the_client_and_shows_channel_indicators() throws Exception {
        Consent consent = registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());
        String externalId = subjects.get(consent.getSubjectId()).getExternalId();

        // UI-3: поиск по внешнему идентификатору и по ФИО — оба способа ведут к одной карточке.
        assertThat(searchFor("Травин", session))
                .contains("Травин Иван Сергеевич")
                .contains("Открыть карточку");

        assertThat(searchFor(externalId, session))
                .as("поиск по внешнему идентификатору «%s» обязан находить клиента", externalId)
                .contains("Открыть карточку");

        assertThat(searchFor("Тр", session)).contains("не менее 3");
    }

    /**
     * UI-0.10: ПДн не попадают в адресную строку.
     *
     * <p>Поиск ведётся по телефону, email и ФИО, поэтому форма отправляется методом POST, а в URL уходит
     * только идентификатор запроса. Раньше значение стояло в query-строке и оседало в истории браузера,
     * заголовке Referer и журналах прокси.
     */
    @Test
    void search_keeps_personal_data_out_of_the_address_bar() throws Exception {
        registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());

        String redirect = mockMvc.perform(post("/ui/subjects/search")
                        .param("query", "+7 916 000-00-11")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        assertThat(redirect).doesNotContain("916").doesNotContain("query=");
        assertThat(redirect).startsWith("/ui/subjects?searchId=");

        // Ссылки пагинации тоже ведут по идентификатору, а не по значению.
        String page = mockMvc.perform(get(redirect).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).doesNotContain("/ui/subjects?query=");
    }

    /**
     * FR-5.2, UI-3: один поиск — одна запись в журнале доступа к ПДн.
     *
     * <p>Индикаторы каналов в строке результата строились через карточку по идентификатору, а та
     * перечитывает субъекта и пишет свою запись: поиск оставлял в журнале одну запись плюс по одной на
     * каждую найденную строку.
     */
    @Test
    void one_search_leaves_exactly_one_record_in_the_personal_data_access_log() throws Exception {
        registerAdvertisingConsent();
        AppUser manager = accounts.create(RoleCode.MANAGER.name());
        MockHttpSession session = loginAs(manager);

        long before = accessLog.count();
        searchFor("Травин", session);
        long after = accessLog.count();

        assertThat(after - before)
                .as("поиск фиксируется одной записью независимо от числа найденных клиентов")
                .isEqualTo(1);

        // §7 и UI-15: журнал обязан называть, кто смотрел данные. При входе через форму идентификатор
        // пользователя терялся — в principal сессии его просто не было, и колонка «кто» оставалась пустой.
        assertThat(accessLog.findAll().stream()
                        .map(ru.example.inconsensu.audit.domain.PdnAccessLogEntry::getUserId)
                        .filter(manager.getId()::equals)
                        .count())
                .as("запись журнала должна ссылаться на вошедшего сотрудника")
                .isPositive();
    }

    /**
     * UI-4: кнопка «Отозвать согласие» в шапке карточки.
     *
     * <p>Кнопка вела на несуществующий маршрут `/ui/subjects/{id}/revocation-dialog` и не делала ничего.
     */
    @Test
    void revocation_dialog_opens_from_the_card_header() throws Exception {
        Consent consent = registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());

        mockMvc.perform(get("/ui/subjects/" + consent.getSubjectId() + "/revocation-dialog")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отзыв согласия")))
                // UI-5: диалог называет согласие, а не печатает его идентификатор.
                .andExpect(content().string(containsString("Реклама по email")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(containsString("Отзываем: <b>" + consent.getId()))));
    }

    /**
     * UI-5: «отозвать все рекламные» вместе с письменным заявлением.
     *
     * <p>Сочетание гарантированно падало: массовый отзыв не передавал доказательства, и проверка
     * FR-8.2 отклоняла операцию, хотя ссылка на скан была указана в форме.
     */
    @Test
    void mass_revocation_by_written_request_accepts_the_document_reference() throws Exception {
        Consent consent = registerAdvertisingConsent();

        mockMvc.perform(post("/ui/consents/" + consent.getId() + "/revoke")
                        .session(loginAs(RoleCode.ADMIN.name()))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("reason", "письменное заявление клиента")
                        .param("revocationSource", "WRITTEN_REQUEST")
                        .param("caseNumber", "ОБР-UI-МАСС")
                        .param("documentRef", "scan://archive/2026/mass.pdf")
                        .param("allAdvertising", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Согласие отозвано")));

        assertThat(consents.get(consent.getId()).consent().getRevokedAt())
                .as("рекламное согласие обязано погаснуть")
                .isNotNull();
    }

    /** UI-4: переключатель «Показать заменённые» действительно добавляет заменённые согласия. */
    @Test
    void superseded_toggle_adds_superseded_consents() throws Exception {
        Consent first = registerAdvertisingConsent();
        UUID subjectId = first.getSubjectId();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());

        // Повторная регистрация по той же форме заменяет прежнее согласие (FR-4.4).
        registerAdvertisingConsentFor(subjects.get(subjectId).getExternalId());

        String withoutSuperseded = mockMvc.perform(
                        get("/ui/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String withSuperseded = mockMvc.perform(
                        get("/ui/subjects/" + subjectId + "?superseded=true").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withoutSuperseded).doesNotContain("заменено новым");
        assertThat(withSuperseded)
                .as("переключатель обязан добавлять заменённые согласия, а не фильтровать уже отфильтрованное")
                .contains("заменено новым");
    }

    /** UI-4: во вкладке передач — русские категории и ссылка на согласие-основание. */
    @Test
    void transfers_tab_names_categories_in_russian_and_links_the_basis() throws Exception {
        Consent consent = registerAdvertisingConsent();

        mockMvc.perform(get("/ui/subjects/" + consent.getSubjectId() + "/tab/transfers")
                        .session(loginAs(RoleCode.ADMIN.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Основание")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(">FIO<"))));
    }

    /** UI-3: панель расширенных фильтров сужает выборку запросом, а не отбором готовой страницы. */
    @Test
    void advanced_filters_narrow_the_search() throws Exception {
        Consent consent = registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());
        String externalId = subjects.get(consent.getSubjectId()).getExternalId();

        String redirect = mockMvc.perform(post("/ui/subjects/search")
                        .param("query", externalId)
                        .session(session)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        String searchId =
                redirect.substring(redirect.indexOf("searchId=") + "searchId=".length(), redirect.indexOf("&"));

        mockMvc.perform(get("/ui/subjects?searchId=" + searchId + "&status=ACTIVE")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Расширенные фильтры")))
                .andExpect(content().string(containsString(externalId)));

        // «Есть отозванные» у клиента без отзывов не должно давать ни одной строки.
        // «Есть отозванные» у клиента без отзывов не должно давать ни одной строки. Сам запрос остаётся
        // в поле поиска, поэтому проверяется таблица результатов, а не текст страницы целиком.
        mockMvc.perform(get("/ui/subjects?searchId=" + searchId + "&revokedOnly=true")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Открыть карточку"))));
    }

    /** UI-2: плитка дашборда открывает отфильтрованный список, а не общий поиск. */
    @Test
    void dashboard_tile_opens_the_filtered_list_without_a_query() throws Exception {
        Consent consent = registerAdvertisingConsent();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());
        String externalId = subjects.get(consent.getSubjectId()).getExternalId();

        mockMvc.perform(get("/ui/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/ui/subjects?status=ACTIVE")));

        mockMvc.perform(get("/ui/subjects?status=ACTIVE").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(externalId)));
    }

    /** Прогон поиска через POST и последующий переход по выданной ссылке — как это делает браузер. */
    private String searchFor(String query, MockHttpSession session) throws Exception {
        String redirect = mockMvc.perform(post("/ui/subjects/search")
                        .param("query", query)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        return mockMvc.perform(get(redirect).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
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

    private Consent registerAdvertisingConsent() {
        return registerAdvertisingConsentFor(null);
    }

    /** Тот же внешний идентификатор — тот же клиент: повторная регистрация заменяет прежнее согласие. */
    private Consent registerAdvertisingConsentFor(String existingExternalId) {
        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                existingExternalId == null
                        ? "CRM-UI-" + UUID.randomUUID().toString().substring(0, 8)
                        : existingExternalId,
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-45", true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL,
                                "travin-ui-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                                true)));

        var result = registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        items,
                        Instant.now(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "заявка интерфейса",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000045",
                                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));

        // Нужен именно рекламный пункт: по нему проверяется, что канал закрывается после отзыва.
        UUID advertisingItemId = itemOf(form, "ADVERTISING_EMAIL").getId();
        return result.created().stream()
                .filter(consent -> advertisingItemId.equals(consent.getFormItemId()))
                .findFirst()
                .orElseThrow();
    }

    private static ConsentFormItem itemOf(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }
}
