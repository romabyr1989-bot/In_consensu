package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
 * Приёмка UI-5 на слое рабочего места: отзыв согласия закрывает канал, а право на него проверяет сервер.
 *
 * <p>Отзыв необратим, поэтому проверяется не наличие кнопки, а поведение операции: кто её может
 * выполнить, что она меняет и что видно после неё. Прятать кнопку недостаточно — отказ должен приходить
 * от сервера.
 */
@AutoConfigureMockMvc
class WorkplaceRevocationApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    private static final String REVOCATION =
            """
            {"reason":"Клиент попросил прекратить рекламу","revocationSource":"CALL_CENTER",
             "caseNumber":"OBR-2026-0007"}
            """;

    @Test
    void revocation_through_the_workplace_closes_the_channel_immediately() throws Exception {
        Consent consent = advertisingConsent();
        UUID subjectId = consent.getSubjectId();
        MockHttpSession session = loginAs(RoleCode.MANAGER.name());

        String before = mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(channel(before, "EMAIL").get("allowed").asBoolean()).isTrue();

        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isOk())
                // UI-5: сообщение называет дату, время и номер обращения — сотруднику есть что сказать клиенту.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OBR-2026-0007")));

        String after = mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var email = channel(after, "EMAIL");
        assertThat(email.get("allowed").asBoolean()).isFalse();
        // UI-0.6: запрет объясняется словами, а не только цветом плитки.
        assertThat(email.get("reason").asText()).contains("отозвано");
    }

    @Test
    void revocation_is_closed_for_a_role_without_the_right() throws Exception {
        Consent consent = advertisingConsent();

        // Приложение E: аудитор читает, но не меняет. Кнопки у него нет, и операция тоже запрещена.
        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(loginAs(RoleCode.AUDITOR.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/ui/api/subjects/" + consent.getSubjectId()).session(loginAs(RoleCode.AUDITOR.name())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayRevoke").value(false));
    }

    @Test
    void request_without_csrf_token_changes_nothing() throws Exception {
        Consent consent = advertisingConsent();
        MockHttpSession session = loginAs(RoleCode.ADMIN.name());

        // Без токена запрос не проходит: иначе чужая страница могла бы отозвать согласие за сотрудника.
        // Ответ — код, а не переход на страницу входа: отсутствие токена сервер считает истёкшей сессией,
        // и приложению нужно отличать это от успеха.
        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:unauthorized"));

        mockMvc.perform(get("/ui/api/subjects/" + consent.getSubjectId()).session(session))
                .andExpect(jsonPath("$.consents[?(@.status == 'REVOKED')]").doesNotExist());
    }

    @Test
    void dialog_offers_only_the_consents_that_can_be_revoked_and_names_the_cascade() throws Exception {
        Consent consent = advertisingConsent();
        MockHttpSession session = loginAs(RoleCode.DPO.name());

        mockMvc.perform(get("/ui/api/subjects/" + consent.getSubjectId() + "/revocable")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[0].title").isNotEmpty());

        // UI-5: последствия называются до подтверждения, а не после.
        mockMvc.perform(get("/ui/api/consents/" + consent.getId() + "/cascade").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void mass_revocation_by_written_request_accepts_the_document_reference() throws Exception {
        Consent consent = advertisingConsent();
        MockHttpSession session = loginAs(RoleCode.DPO.name());

        // FR-8.2: письменное заявление требует ссылки на скан — без неё отказ, с ней гасятся все рекламные.
        mockMvc.perform(
                        post("/ui/api/consents/" + consent.getId() + "/revoke")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"reason":"Заявление в офисе","revocationSource":"WRITTEN_REQUEST",
                                 "caseNumber":"OBR-2026-0008","allAdvertising":true}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/ui/api/consents/" + consent.getId() + "/revoke")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"reason":"Заявление в офисе","revocationSource":"WRITTEN_REQUEST",
                                 "caseNumber":"OBR-2026-0008","documentRef":"scan-2026-0008.pdf",
                                 "allAdvertising":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Погашено согласий")));
    }

    @Test
    void dossier_of_the_revoked_consent_keeps_the_evidence_and_names_the_reason() throws Exception {
        Consent consent = advertisingConsent();
        MockHttpSession session = loginAs(RoleCode.DPO.name());

        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ui/api/consents/" + consent.getId()).session(session))
                .andExpect(status().isOk())
                // UI-4a: досье остаётся доказательством и после отзыва — сумма текста сходится.
                .andExpect(jsonPath("$.checksumMatches").value(true))
                .andExpect(jsonPath("$.integrityOk").value(true))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty())
                .andExpect(jsonPath("$.revocationSourceRu").value("Звонок в колл-центр"))
                .andExpect(jsonPath("$.revocationReason").value("Клиент попросил прекратить рекламу"))
                // NFR-3: чувствительные значения доказательств маскируются и здесь.
                .andExpect(jsonPath("$.evidence.phone").value("***"));
    }

    private static com.fasterxml.jackson.databind.JsonNode channel(String json, String code) throws Exception {
        var channels =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("channels");
        for (var channel : channels) {
            if (code.equals(channel.get("channel").asText())) {
                return channel;
            }
        }
        throw new AssertionError("В карточке нет канала " + code);
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /** Согласие на рекламу по почте: по нему видно, что канал закрывается сразу после отзыва. */
    private Consent advertisingConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-REV-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-45", true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL,
                                "travin-rev-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
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
                        "заявка рабочего места",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000045",
                                "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));

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
