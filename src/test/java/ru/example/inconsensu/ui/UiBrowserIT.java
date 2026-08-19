package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlPasswordInput;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.htmlunit.MockMvcWebClientBuilder;
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
 * Приёмка §16: интерфейс проходится браузерным клиентом — форма входа заполняется и отправляется так же,
 * как это делает сотрудник.
 *
 * <p>JavaScript выключен намеренно: проверяется то, что сервер отдаёт в HTML. Если карточка окажется
 * пустой без скриптов, значит рендер уехал на клиент, а §16 требует серверного рендеринга.
 */
@AutoConfigureMockMvc
class UiBrowserIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ConsentRegistrationService registration;

    private WebClient browser;

    @BeforeEach
    void openBrowser() {
        browser = MockMvcWebClientBuilder.mockMvcSetup(mockMvc).build();
        browser.getOptions().setJavaScriptEnabled(false);
        browser.getOptions().setCssEnabled(false);
        browser.getOptions().setThrowExceptionOnFailingStatusCode(false);
    }

    @AfterEach
    void closeBrowser() {
        browser.close();
    }

    @Test
    void employee_signs_in_through_the_form_and_sees_the_card_with_channels() throws Exception {
        AppUser user = accounts.create(RoleCode.MANAGER.name());
        Consent consent = registerConsent();

        HtmlPage loginPage = browser.getPage("http://localhost/ui/login");
        assertThat(loginPage.asNormalizedText()).contains("Рабочее место сотрудника");

        HtmlTextInput login = loginPage.querySelector("#username");
        HtmlPasswordInput password = loginPage.querySelector("#password");
        login.type(user.getLogin());
        password.type(TestAccounts.PASSWORD);

        HtmlButton submit = loginPage.querySelector("button[type=submit]");
        HtmlPage dashboard = submit.click();
        assertThat(dashboard.asNormalizedText()).contains("Действующих согласий");

        HtmlPage card = browser.getPage("http://localhost/ui/subjects/" + consent.getSubjectId());
        String text = card.asNormalizedText();
        assertThat(text).contains("Телефонный звонок").contains("Электронная почта");
        assertThat(text).contains("Согласия");
        // UI-0.10: ФИО клиента не выводится в заголовок окна браузера.
        assertThat(card.getTitleText()).doesNotContain("Травин");
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        var items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-BROWSER-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-47", true)));

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
                                "браузерный сценарий",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000047",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }
}
