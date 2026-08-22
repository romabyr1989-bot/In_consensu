package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * §16.5 и §7: ПДн не попадают в журналы приложения — теперь на слое данных рабочего места.
 *
 * <p>Требование проверялось для экранов Thymeleaf ({@code PersonalDataInLogsIT}), но рабочее место
 * переехало на `/ui/api` (ADR-0087), и через эти адреса идут ровно те же значения: телефон приходит телом
 * поиска, ФИО уходит в ответе досье, раскрытый контакт возвращается целиком. Требование от смены
 * интерфейса не изменилось, а места утечки — другие, поэтому сценарий повторяется по новым адресам.
 *
 * <p>Проверяются и сообщения, и текст исключений: ПДн чаще утекают именно через них, а на `/ui/api`
 * отказы обрабатывает не страничный обработчик, а общий — с другим набором записей в журнал.
 */
@AutoConfigureMockMvc
class PersonalDataInLogsWorkplaceIT extends AbstractIntegrationTest {

    private static final String REVOCATION =
            """
            {"reason":"проверка отказа","revocationSource":"CALL_CENTER"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    private final ListAppender<ILoggingEvent> recorded = new ListAppender<>();

    private String surname;
    private String phone;
    private String phoneDigits;
    private String email;
    private String externalId;

    @BeforeEach
    void startRecording() {
        recorded.start();
        rootLogger().addAppender(recorded);
    }

    @AfterEach
    void stopRecording() {
        rootLogger().detachAppender(recorded);
        recorded.stop();
    }

    @Test
    void personal_data_never_reaches_the_application_log() throws Exception {
        Consent consent = advertisingConsent();
        UUID subjectId = consent.getSubjectId();
        MockHttpSession manager = loginAs(RoleCode.MANAGER.name());

        // Обычная работа сотрудника: поиск по телефону, карточка, раскрытие контакта, досье, отзыв.
        String found = mockMvc.perform(post("/ui/api/subjects/search")
                        .session(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"%s\"}".formatted(phone)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // Без этого телефон мог бы вовсе не дойти до сервера, и проверка журнала ничего бы не значила.
        assertThat(found).as("поиск по телефону должен найти клиента").contains(subjectId.toString());

        mockMvc.perform(get("/ui/api/subjects/" + subjectId).session(manager)).andExpect(status().isOk());

        mockMvc.perform(post("/ui/api/subjects/" + subjectId + "/reveal")
                        .session(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\"}".formatted(ContactType.PHONE.name())))
                .andExpect(status().isOk());

        String dossier = mockMvc.perform(
                        get("/ui/api/consents/" + consent.getId()).session(manager))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // ФИО в ответе — это норма: сотрудник его и запрашивал. Запрещено оно только в журнале.
        assertThat(dossier).as("досье должно называть клиента").contains(surname);

        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isOk());

        // Отказы и ошибки — самый частый источник утечки: текст исключения уходит в лог целиком.
        mockMvc.perform(post("/ui/api/subjects/search")
                        .session(loginAs(RoleCode.INTEGRATION.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"%s\"}".formatted(phone)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/ui/api/subjects/" + UUID.randomUUID()).session(manager))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/ui/api/consents/" + consent.getId() + "/revoke")
                        .session(loginAs(RoleCode.MARKETING.name()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REVOCATION))
                .andExpect(status().isForbidden());

        // Контроль: приложение действительно писало в перехваченный журнал во время этих запросов.
        // Без него проверка «ПДн нет» проходила бы и на пустом журнале — то есть не значила бы ничего.
        assertThat(logText())
                .as("в журнале должны быть следы обработанных запросов")
                .contains("Запрос отклонён");
        List<String> secrets = List.of(surname, phone, phoneDigits, email, externalId);
        for (String secret : secrets) {
            assertThat(logText())
                    .as("ПДн «%s» не должны попадать в журнал (§7, §16.5)", secret)
                    .doesNotContain(secret);
        }
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(recorded.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            var thrown = event.getThrowableProxy();
            while (thrown != null) {
                text.append(thrown.getClassName())
                        .append(": ")
                        .append(thrown.getMessage())
                        .append('\n');
                thrown = thrown.getCause();
            }
        }
        return text.toString();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /**
     * Согласие на рекламу по почте: его отзыв разрешён и не тянет за собой обязательную обработку ПДн.
     *
     * <p>Данные вымышленные и уникальные на прогон: совпадение с чужой записью сделало бы проверку слепой.
     */
    private Consent advertisingConsent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // Номер собирается из цифр: подстрока UUID шестнадцатеричная, и буква в ней ломает проверку телефона.
        String digits = String.format("%04d", Math.abs(UUID.randomUUID().hashCode() % 10_000));
        surname = "Заозёрская";
        phone = "+7 916 044-" + digits.substring(0, 2) + "-" + digits.substring(2, 4);
        phoneDigits = phone.replace(" ", "").replace("-", "");
        email = "zaozerskaya-" + tag + "@example.ru";
        externalId = "CRM-LOGAPI-" + tag;

        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                externalId,
                surname,
                "Ольга",
                "Петровна",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, phone, true),
                        new SubjectService.ContactForm(ContactType.EMAIL, email, true)));

        var result = registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        items,
                        Instant.now(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "заявка проверки журналов",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", phoneDigits,
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
