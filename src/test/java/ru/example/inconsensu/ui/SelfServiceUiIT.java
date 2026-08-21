package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.integration.application.SelfUiSessionService;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestForms;

/** Приёмка UI-18: одноразовая ссылка, страница клиента и отзыв, который сразу виден сотруднику. */
@AutoConfigureMockMvc
class SelfServiceUiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SelfUiSessionService uiSessions;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private SubjectCardService cards;

    @Autowired
    private ru.example.inconsensu.common.config.InConsensuProperties properties;

    @Test
    void one_time_link_opens_the_page_and_stops_working_afterwards() throws Exception {
        Consent consent = registerConsent();
        String token = tokenOf(issueLink(consent));

        MockHttpSession session =
                (MockHttpSession) mockMvc.perform(get("/self/ui").param("token", token))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        mockMvc.perform(get("/self/ui").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Здравствуйте")))
                .andExpect(content().string(containsString("Отказаться от всей рекламы")))
                // UI-0.10: на странице клиента нет ни адреса, ни телефона — только его согласия.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("@example.ru"))));

        // Ссылка одноразовая: второе открытие обязано показать «недействительна», а не чужие согласия.
        mockMvc.perform(get("/self/ui").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ссылка недействительна")));
    }

    @Test
    void revocation_from_the_client_page_is_visible_to_the_employee_at_once() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session =
                (MockHttpSession) mockMvc.perform(get("/self/ui").param("token", tokenOf(issueLink(consent))))
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        mockMvc.perform(post("/self/ui/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Согласие отозвано")));

        var card = cards.cardOf(consent.getSubjectId());
        assertThat(card.channels()).anySatisfy(decision -> assertThat(decision.allowed())
                .as("после отзыва канал обязан закрыться")
                .isFalse());
    }

    /**
     * UI-18: отзыв клиентом подтверждается в два шага.
     *
     * <p>Кнопка на карточке только открывает окно; сам отзыв происходит из этого окна. Раньше страница
     * спрашивала одним диалогом браузера, и необратимое действие отделял от клиента один щелчок.
     */
    @Test
    void client_confirms_revocation_in_two_steps() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session =
                (MockHttpSession) mockMvc.perform(get("/self/ui").param("token", tokenOf(issueLink(consent))))
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        // Первый шаг: на странице стоит кнопка, которая открывает окно, а не отправляет отзыв.
        mockMvc.perform(get("/self/ui").session(session))
                .andExpect(content().string(containsString("/revoke-dialog")))
                .andExpect(content().string(containsString("/revoke-all-advertising-dialog")));

        // Второй шаг: окно называет последствия и несёт форму отзыва.
        mockMvc.perform(get("/self/ui/consents/" + consent.getId() + "/revoke-dialog")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отозвать согласие?")))
                .andExpect(content().string(containsString("Отзыв необратим")))
                .andExpect(content().string(containsString("Да, отозвать")));

        mockMvc.perform(get("/self/ui/consents/revoke-all-advertising-dialog").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отказаться от всей рекламы?")))
                .andExpect(content().string(containsString("Да, отказаться")));

        mockMvc.perform(post("/self/ui/consents/" + consent.getId() + "/revoke")
                        .session(session)
                        .with(csrf()))
                .andExpect(content().string(containsString("Согласие отозвано")));

        // Отозванное согласие второй раз не подтверждается: окно объясняет это словами, а не молчит.
        mockMvc.perform(get("/self/ui/consents/" + consent.getId() + "/revoke-dialog")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("уже нельзя отозвать")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Да, отозвать"))));
    }

    /**
     * UI-18: сроки жизни ссылки и сессии страницы настраиваются.
     *
     * <p>Оба значения были константами в коде: оператор с более строгой политикой не мог их сократить, а ТЗ
     * называет их настраиваемыми. Проверяется само связывание настройки — время в тесте не мотается.
     */
    @Test
    void link_and_session_lifetimes_come_from_the_configuration() {
        assertThat(properties.selfservice().linkTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(properties.selfservice().sessionTtl()).isEqualTo(java.time.Duration.ofMinutes(15));

        Consent consent = registerConsent();
        var issued = RunAs.roles(
                "test-integration",
                List.of("INTEGRATION"),
                () -> uiSessions.issue(RunAs.roles(
                        "test-integration",
                        List.of("INTEGRATION"),
                        () -> cards.cardOf(consent.getSubjectId()).subject().getExternalId())));

        assertThat(issued.expiresAt())
                .as("срок ссылки обязан считаться от настройки, а не от константы")
                .isBefore(Instant.now().plus(properties.selfservice().linkTtl()).plusSeconds(5));
    }

    /**
     * FR-8.1: ограничение частоты действует и на встраиваемую страницу.
     *
     * <p>Лимит стоял на входе в API самообслуживания, а страница работает по открытой сессии и шла мимо
     * него: «Отозвать» в цикле не упиралось ни во что.
     */
    @Test
    void repeated_revocations_from_the_page_run_into_the_rate_limit() throws Exception {
        Consent consent = registerConsent();
        MockHttpSession session =
                (MockHttpSession) mockMvc.perform(get("/self/ui").param("token", tokenOf(issueLink(consent))))
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        // Отзыв идемпотентен, поэтому повторы проходят по тому же пути и упираются именно в предел частоты.
        int limited = 0;
        for (int attempt = 0; attempt < 40; attempt++) {
            int status = mockMvc.perform(post("/self/ui/consents/" + consent.getId() + "/revoke")
                            .session(session)
                            .with(csrf()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status == 429) {
                limited++;
                break;
            }
        }

        assertThat(limited)
                .as("страница обязана упереться в ограничение частоты")
                .isPositive();
    }

    @Test
    void page_without_a_session_asks_to_return_to_the_personal_account() throws Exception {
        mockMvc.perform(get("/self/ui"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ссылка недействительна")));
    }

    private String issueLink(Consent consent) {
        String externalId =
                RunAs.roles("test-integration", List.of("INTEGRATION"), () -> cards.cardOf(consent.getSubjectId())
                        .subject()
                        .getExternalId());
        return RunAs.roles("test-integration", List.of("INTEGRATION"), () -> uiSessions
                .issue(externalId)
                .url());
    }

    /** Ссылка выдаётся абсолютной; тесту нужен только сам токен. */
    private static String tokenOf(String url) {
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        var items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();
        UUID advertisingItemId = form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow()
                .getId();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-SELF-" + UUID.randomUUID().toString().substring(0, 8),
                "Бондаренко",
                "Мария",
                "Олеговна",
                null,
                List.of(new SubjectService.ContactForm(
                        ContactType.EMAIL,
                        "self-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                        true)));

        return registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                subject,
                                form.getId(),
                                items,
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "личный кабинет",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000046",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .stream()
                .filter(consent -> advertisingItemId.equals(consent.getFormItemId()))
                .findFirst()
                .orElseThrow();
    }
}
