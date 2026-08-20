package ru.example.inconsensu.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.iam.application.OperatorSettingsService;

/** Готовит опубликованную форму: почти каждому сценарию этапов 3+ нужна форма, а не путь её согласования. */
@TestComponent
public class TestForms {

    private static final String BODY =
            "Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}} ({{operator.address}}).";

    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final OperatorSettingsService settings;

    public TestForms(ConsentFormService forms, FormWorkflowService workflow, OperatorSettingsService settings) {
        this.forms = forms;
        this.workflow = workflow;
        this.settings = settings;
    }

    public void fillOperatorRequisites() {
        RunAs.rolesVoid(
                "test-admin",
                List.of("ADMIN"),
                () -> settings.update(Map.of(
                        "operator.name", "ООО «Тестовый оператор»",
                        "operator.address", "123001, Москва, ул. Тестовая, д. 1")));
    }

    /** Форма с двумя пунктами: базовая обработка ПДн и реклама по email. */
    public ConsentForm publishTwoItemForm() {
        return publish(List.of(
                new ConsentFormService.ItemForm(
                        "PDN_PROCESSING",
                        "Согласие на обработку персональных данных",
                        List.of("рассмотрение заявки"),
                        List.of("FIO", "PHONE", "EMAIL"),
                        null,
                        null,
                        true),
                new ConsentFormService.ItemForm(
                        "ADVERTISING_EMAIL",
                        "Согласие на рекламу по электронной почте",
                        List.of("информирование о продуктах"),
                        List.of("EMAIL"),
                        null,
                        "P1Y",
                        false)));
    }

    public ConsentForm publish(List<ConsentFormService.ItemForm> items) {
        fillOperatorRequisites();
        String code = "IT_FORM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ConsentForm draft = RunAs.roles(
                "test-lawyer",
                List.of("LAWYER"),
                () -> forms.createDraft(
                        code,
                        new ConsentFormService.FormDraft(
                                "Форма для интеграционного теста",
                                BODY,
                                "сбор, запись, хранение, уничтожение",
                                "действует до отзыва; отзыв — в личном кабинете",
                                Set.of(ConsentSource.WEBSITE_APPLICATION),
                                items)));

        RunAs.rolesVoid("test-lawyer", List.of("LAWYER"), () -> workflow.submit(draft.getId()));
        RunAs.rolesVoid("test-lawyer", List.of("LAWYER"), () -> workflow.approve(draft.getId(), "ок"));
        RunAs.rolesVoid("test-dpo", List.of("DPO"), () -> workflow.approve(draft.getId(), "ок"));
        return RunAs.roles("test-dpo", List.of("DPO"), () -> workflow.publish(draft.getId()));
    }

    /** Черновик следующей версии опубликованной формы: нужен проверкам правила FR-2.3. */
    public ConsentForm draftNewVersionOf(String code) {
        ConsentForm published = forms.publishedVersionOf(code).orElseThrow();
        return RunAs.roles("test-lawyer", List.of("LAWYER"), () -> forms.createNewVersion(published.getId()));
    }

    /** Форма с пунктом передачи данных конкретному третьему лицу — нужна сценариям §7.7. */
    public ConsentForm publishFormWithTransfer(java.util.UUID thirdPartyId, List<String> categories) {
        return publish(List.of(
                new ConsentFormService.ItemForm(
                        "PDN_PROCESSING",
                        "Согласие на обработку персональных данных",
                        List.of("рассмотрение заявки"),
                        List.of("FIO", "PHONE", "EMAIL"),
                        null,
                        null,
                        true),
                new ConsentFormService.ItemForm(
                        "PDN_TRANSFER",
                        "Согласие на передачу персональных данных",
                        List.of("доставка корреспонденции"),
                        categories,
                        thirdPartyId,
                        "P1Y",
                        false)));
    }
}
