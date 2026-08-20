package ru.example.inconsensu.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.domain.ConsentForm;
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
 * §16.5 и §7: ПДн не попадают в журналы приложения.
 *
 * <p>Адресную строку и заголовок окна проверяют {@code UiRevocationIT} и {@code UiBrowserIT}. Здесь
 * закрывается третье место из того же требования — логи: сотрудник ищет клиента по телефону, открывает
 * карточку, раскрывает контакт и получает отказы, а в журнале не должно остаться ни фамилии, ни
 * телефона, ни адреса почты. Проверяются и сообщения, и текст исключений: ПДн чаще утекают именно через
 * них.
 */
@AutoConfigureMockMvc
class PersonalDataInLogsIT extends AbstractIntegrationTest {

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
    private String email;

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
        Consent consent = registerConsent();
        MockHttpSession manager = loginAs(RoleCode.MANAGER.name());

        // Обычная работа сотрудника: поиск по телефону, карточка, раскрытие контакта, досье согласия.
        String redirect = mockMvc.perform(post("/ui/subjects/search")
                        .param("query", phone)
                        .session(manager)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        mockMvc.perform(get(redirect).session(manager));
        mockMvc.perform(get("/ui/subjects/" + consent.getSubjectId()).session(manager));
        mockMvc.perform(post("/ui/subjects/" + consent.getSubjectId() + "/reveal")
                .param("type", ContactType.PHONE.name())
                .session(manager)
                .with(csrf()));
        mockMvc.perform(get("/ui/consents/" + consent.getId()).session(manager));
        mockMvc.perform(get("/ui/consents/" + consent.getId() + "/text").session(manager));

        // Отказы и ошибки — самый частый источник утечки: текст исключения уходит в лог целиком.
        mockMvc.perform(post("/ui/subjects/search")
                .param("query", phone)
                .session(loginAs(RoleCode.INTEGRATION.name()))
                .with(csrf()));
        mockMvc.perform(get("/ui/subjects/" + UUID.randomUUID()).session(manager));
        mockMvc.perform(post("/ui/consents/" + consent.getId() + "/revoke")
                .param("reason", "проверка отказа")
                .param("revocationSource", "CALL_CENTER")
                .session(loginAs(RoleCode.MARKETING.name()))
                .with(csrf()));

        // Контроль: приложение действительно писало в перехваченный журнал во время этих запросов.
        // Без него проверка «ПДн нет» проходила бы и на пустом журнале — то есть не значила бы ничего.
        assertThat(logText())
                .as("в журнале должны быть следы обработанных запросов")
                .contains("Запрос отклонён");
        for (String secret :
                List.of(surname, phone, email, phone.replace(" ", "").replace("-", ""))) {
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

    /** Данные вымышленные и уникальные на прогон: совпадение с чужой записью сделало бы проверку слепой. */
    private Consent registerConsent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // Номер собирается из цифр: подстрока UUID шестнадцатеричная, и буква в ней ломает проверку телефона.
        String digits = String.format("%04d", Math.abs(UUID.randomUUID().hashCode() % 10_000));
        surname = "Заозёрская";
        phone = "+7 916 044-" + digits.substring(0, 2) + "-" + digits.substring(2, 4);
        email = "zaozerskaya-" + tag + "@example.ru";

        ConsentForm form = testForms.publishTwoItemForm();
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-LOG-" + tag,
                surname,
                "Ольга",
                "Петровна",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, phone, true),
                        new SubjectService.ContactForm(ContactType.EMAIL, email, true)));

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
                                "заявка проверки журналов",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", phone.replace(" ", "").replace("-", ""),
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .getFirst();
    }
}
