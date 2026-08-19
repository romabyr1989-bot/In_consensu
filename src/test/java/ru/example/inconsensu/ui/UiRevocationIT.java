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
        mockMvc.perform(get("/ui/subjects").param("query", "Травин").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Травин Иван Сергеевич")))
                .andExpect(content().string(containsString("Открыть карточку")));

        String byExternalId = mockMvc.perform(
                        get("/ui/subjects").param("query", externalId).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(byExternalId)
                .as("поиск по внешнему идентификатору «%s» обязан находить клиента", externalId)
                .contains("Открыть карточку");

        mockMvc.perform(get("/ui/subjects").param("query", "Тр").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("не менее 3")));
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private Consent registerAdvertisingConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-UI-" + UUID.randomUUID().toString().substring(0, 8),
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
