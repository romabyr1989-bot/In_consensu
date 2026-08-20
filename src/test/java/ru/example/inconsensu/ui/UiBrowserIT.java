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
    private ru.example.inconsensu.catalog.application.ConsentFormService forms;

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ru.example.inconsensu.registry.application.ConsentQueryService consents;

    @Autowired
    private ru.example.inconsensu.catalog.application.ConsentTypeService types;

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

    /**
     * UI-8: на пустом черновике кнопка «Добавить пункт» действительно добавляет пункт.
     *
     * <p>Скрипт копировал первый существующий пункт и на свежем черновике молча ничего не делал — форму
     * Приложения C нельзя было собрать в конструкторе, то есть приёмочный критерий §16 не выполнялся.
     * Проверяется с включённым JavaScript: разметка сама по себе дефект не показывает.
     */
    @Test
    void the_builder_adds_the_first_item_to_an_empty_draft() throws Exception {
        AppUser lawyer = accounts.create(RoleCode.LAWYER.name());
        String code = "UI8_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ConsentForm draft = forms.createDraft(
                code,
                new ru.example.inconsensu.catalog.application.ConsentFormService.FormDraft(
                        "Пустой черновик", "Тело", "сбор", "до отзыва", java.util.Set.of(), List.of()));

        browser.getOptions().setJavaScriptEnabled(true);
        // Движок HtmlUnit не разбирает современный синтаксис htmx; проверяется собственный скрипт
        // конструктора, поэтому ошибки чужих библиотек не должны ронять тест.
        browser.getOptions().setThrowExceptionOnScriptError(false);
        signIn(lawyer);

        HtmlPage page = browser.getPage("http://localhost/ui/catalog/forms/" + draft.getId() + "/edit");
        assertThat(page.querySelectorAll("#items .ic-item"))
                .as("у свежего черновика пунктов нет")
                .isEmpty();

        HtmlButton add = page.querySelector("button[onclick='cusAddItem()']");
        add.click();

        assertThat(page.querySelectorAll("#items .ic-item"))
                .as("кнопка «Добавить пункт» обязана работать и на пустом черновике")
                .hasSize(1);
        // Явный тип: querySelector обобщённый, и вывод типа делает вызов assertThat неоднозначным.
        org.htmlunit.html.DomElement typeField = page.querySelector("#items .ic-item [name='items[0].typeCode']");
        assertThat(typeField).as("поля нового пункта нумеруются с нуля").isNotNull();
    }

    /**
     * §16.5: сценарий «отзыв → канал запрещён» проходится через интерфейс браузерным клиентом.
     *
     * <p>MockMvc-проверки этого сценария уже есть, но приёмка §16 требует именно браузерного прохода:
     * форма отзыва отправляется как сотрудником, а карточка перечитывается заново.
     */
    @Test
    void revocation_through_the_browser_closes_the_channel() throws Exception {
        AppUser manager = accounts.create(RoleCode.ADMIN.name());
        Consent consent = advertisingConsent();
        signIn(manager);

        HtmlPage card = browser.getPage("http://localhost/ui/subjects/" + consent.getSubjectId());
        assertThat(card.asNormalizedText()).contains("Электронная почта").contains("можно");

        HtmlPage dialog = browser.getPage("http://localhost/ui/consents/" + consent.getId() + "/revocation-dialog");
        // UI-5: диалог называет отзываемое согласие, а не его идентификатор.
        assertThat(dialog.asNormalizedText()).contains("Реклама по email");

        HtmlTextInput caseNumber = dialog.querySelector("#caseNumber");
        caseNumber.type("ОБР-БРАУЗЕР-1");
        org.htmlunit.html.HtmlTextArea reason = dialog.querySelector("#reason");
        reason.type("клиент попросил прекратить рекламу");
        org.htmlunit.html.HtmlSelect source = dialog.querySelector("#revocationSource");
        source.setSelectedAttribute("CALL_CENTER", true);
        ((HtmlButton) dialog.querySelector("button[type=submit]")).click();

        HtmlPage afterRevoke = browser.getPage("http://localhost/ui/subjects/" + consent.getSubjectId());
        assertThat(afterRevoke.asNormalizedText())
                .as("канал обязан закрыться сразу после отзыва")
                .contains("Реклама по email запрещена: согласие отозвано");
    }

    /** Рекламное согласие того же клиента: по нему проверяется закрытие канала после отзыва. */
    private Consent advertisingConsent() {
        Consent any = registerConsent();
        return consents.currentConsentsOf(any.getSubjectId()).stream()
                .map(ru.example.inconsensu.registry.application.ConsentQueryService.ConsentView::consent)
                .filter(consent ->
                        types.get(consent.getConsentTypeId()).getCode().equals("ADVERTISING_EMAIL"))
                .findFirst()
                .orElseThrow();
    }

    private void signIn(AppUser user) throws Exception {
        HtmlPage loginPage = browser.getPage("http://localhost/ui/login");
        HtmlTextInput login = loginPage.querySelector("#username");
        HtmlPasswordInput password = loginPage.querySelector("#password");
        login.setValue("");
        login.type(user.getLogin());
        password.setValue("");
        password.type(TestAccounts.PASSWORD);
        ((HtmlButton) loginPage.querySelector("button[type=submit]")).click();
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
