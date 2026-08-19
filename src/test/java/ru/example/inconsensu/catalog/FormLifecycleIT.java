package ru.example.inconsensu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/**
 * Приёмка этапа 2 (§13): путь черновик → на согласовании → одобрено обеими ролями → опубликовано →
 * новая версия → предыдущая в архиве.
 */
class FormLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private OperatorSettingsService settings;

    @Autowired
    private ConsentFormService forms;

    private HttpHeaders lawyer;
    private HttpHeaders dpo;

    @BeforeEach
    void setUp() {
        // FR-1.3: без реквизитов оператора форма не пройдёт проверку, поэтому заполняем их как это сделал бы админ.
        settings.update(Map.of(
                "operator.name", "ООО «Тестовый оператор»",
                "operator.address", "123001, Москва, ул. Тестовая, д. 1"));
        lawyer = accounts.authorizationFor(RoleCode.LAWYER.name());
        dpo = accounts.authorizationFor(RoleCode.DPO.name());
    }

    private Map<String, Object> validForm(String title) {
        return Map.of(
                "title",
                title,
                "body",
                "Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}} "
                        + "({{operator.address}}) на обработку персональных данных.",
                "processingActions",
                "сбор, запись, систематизация, хранение, уничтожение",
                "revocationProcedure",
                "действует до отзыва; отзыв — в личном кабинете или письменным заявлением",
                "sourceChannels",
                List.of("WEBSITE_APPLICATION"),
                "items",
                List.of(
                        Map.of(
                                "consentTypeCode",
                                "PDN_PROCESSING",
                                "text",
                                "Согласие на обработку персональных данных",
                                "purposes",
                                List.of("рассмотрение заявки и заключение договора"),
                                "pdnCategories",
                                List.of("FIO", "PHONE", "EMAIL"),
                                "mandatory",
                                true),
                        Map.of(
                                "consentTypeCode",
                                "ADVERTISING_EMAIL",
                                "text",
                                "Согласие на рекламу по электронной почте",
                                "purposes",
                                List.of("информирование о продуктах и акциях"),
                                "pdnCategories",
                                List.of("EMAIL"),
                                "mandatory",
                                false)));
    }

    private <T> ResponseEntity<T> call(String path, HttpMethod method, HttpHeaders auth, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(auth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDraft(String code, String title) {
        ResponseEntity<Map> created = call(
                "/api/v1/forms", HttpMethod.POST, lawyer, Map.of("code", code, "form", validForm(title)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    @Test
    void draft_goes_through_review_to_publication_and_a_new_version_archives_the_previous_one() {
        String code =
                "TEST_FORM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> draft = createDraft(code, "Согласие на сайте");
        String id = (String) draft.get("id");

        assertThat(draft.get("status")).isEqualTo("DRAFT");
        assertThat(draft.get("version")).isEqualTo(1);
        assertThat((List<?>) draft.get("items")).hasSize(2);

        assertThat(call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, Map.class)
                        .getBody()
                        .get("status"))
                .isEqualTo("ON_REVIEW");

        // Одного одобрения мало: по FR-2.1 нужны обе роли из inconsensu.approval.required-roles.
        assertThat(call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, lawyer, Map.of("comment", "ок"), Map.class)
                        .getBody()
                        .get("status"))
                .isEqualTo("ON_REVIEW");

        assertThat(call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, dpo, Map.of("comment", "ок"), Map.class)
                        .getBody()
                        .get("status"))
                .isEqualTo("APPROVED");

        Map<String, Object> published = call("/api/v1/forms/" + id + "/publish", HttpMethod.POST, dpo, null, Map.class)
                .getBody();
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat((String) published.get("checksum")).startsWith("sha256:");
        assertThat(published.get("validFrom")).isNotNull();

        Map<String, Object> second = call(
                        "/api/v1/forms/" + id + "/new-version", HttpMethod.POST, lawyer, null, Map.class)
                .getBody();
        assertThat(second.get("version")).isEqualTo(2);
        assertThat(second.get("status")).isEqualTo("DRAFT");
        assertThat((List<?>) second.get("items")).hasSize(2);

        String secondId = (String) second.get("id");
        call("/api/v1/forms/" + secondId + "/submit", HttpMethod.POST, lawyer, null, Map.class);
        call("/api/v1/forms/" + secondId + "/approve", HttpMethod.POST, lawyer, Map.of("comment", "ок"), Map.class);
        call("/api/v1/forms/" + secondId + "/approve", HttpMethod.POST, dpo, Map.of("comment", "ок"), Map.class);
        call("/api/v1/forms/" + secondId + "/publish", HttpMethod.POST, dpo, null, Map.class);

        Map<String, Object> first = call("/api/v1/forms/" + id, HttpMethod.GET, lawyer, null, Map.class)
                .getBody();
        assertThat(first.get("status")).isEqualTo("ARCHIVED");
        assertThat(first.get("validTo")).isNotNull();
    }

    @Test
    void form_with_violations_does_not_reach_review() {
        Map<String, Object> broken = new java.util.HashMap<>(validForm("Без целей"));
        broken.put(
                "items",
                List.of(Map.of(
                        "consentTypeCode",
                        "PDN_PROCESSING",
                        "text",
                        "Текст",
                        "purposes",
                        List.of(),
                        "pdnCategories",
                        List.of("FIO"),
                        "mandatory",
                        true)));
        String code = "BROKEN_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String id =
                (String) call("/api/v1/forms", HttpMethod.POST, lawyer, Map.of("code", code, "form", broken), Map.class)
                        .getBody()
                        .get("id");

        ResponseEntity<String> submit =
                call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, String.class);

        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit.getBody())
                .contains("urn:inconsensu:error:validation-failed")
                .contains("не указана цель");
        assertThat(call("/api/v1/forms/" + id, HttpMethod.GET, lawyer, null, Map.class)
                        .getBody()
                        .get("status"))
                .isEqualTo("DRAFT");
    }

    @Test
    void rejection_requires_a_comment_and_returns_the_form_to_draft() {
        String code = "REJECT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String id = (String) createDraft(code, "Возврат на доработку").get("id");
        call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, Map.class);

        assertThat(call("/api/v1/forms/" + id + "/reject", HttpMethod.POST, dpo, Map.of("comment", ""), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(call(
                                "/api/v1/forms/" + id + "/reject",
                                HttpMethod.POST,
                                dpo,
                                Map.of("comment", "уточните цели"),
                                Map.class)
                        .getBody()
                        .get("status"))
                .isEqualTo("DRAFT");

        // FR-2.2: история решений сохраняется целиком.
        ResponseEntity<List> history =
                call("/api/v1/forms/" + id + "/history", HttpMethod.GET, lawyer, null, List.class);
        assertThat(history.getBody()).isNotEmpty();
    }

    @Test
    void approvals_of_a_previous_round_do_not_count_after_rework() {
        String code = "ROUND_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String id = (String) createDraft(code, "Повторный круг").get("id");

        call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, Map.class);
        call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, lawyer, Map.of("comment", "ок"), Map.class);
        call("/api/v1/forms/" + id + "/reject", HttpMethod.POST, dpo, Map.of("comment", "переделать"), Map.class);

        call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, Map.class);
        // Одобрение юриста из прошлого круга не должно засчитаться: нужно снова одобрение обеих ролей.
        String afterDpoOnly = (String)
                call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, dpo, Map.of("comment", "ок"), Map.class)
                        .getBody()
                        .get("status");

        assertThat(afterDpoOnly).isEqualTo("ON_REVIEW");
    }

    @Test
    void published_form_cannot_be_edited_and_publication_is_closed_for_the_lawyer() {
        String code = "LOCKED_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String id = (String) createDraft(code, "Неизменяемая").get("id");
        call("/api/v1/forms/" + id + "/submit", HttpMethod.POST, lawyer, null, Map.class);
        call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, lawyer, Map.of("comment", "ок"), Map.class);
        call("/api/v1/forms/" + id + "/approve", HttpMethod.POST, dpo, Map.of("comment", "ок"), Map.class);

        // FR-2.1: публикует DPO или ADMIN, юрист — нет.
        assertThat(call("/api/v1/forms/" + id + "/publish", HttpMethod.POST, lawyer, null, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        call("/api/v1/forms/" + id + "/publish", HttpMethod.POST, dpo, null, Map.class);

        // FR-1.5: опубликованная версия неизменяема.
        assertThat(call("/api/v1/forms/" + id, HttpMethod.PUT, lawyer, validForm("Попытка правки"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void preview_and_text_show_the_document_with_and_without_subject_data() {
        String code = "PREVIEW_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String id = (String) createDraft(code, "Предпросмотр").get("id");

        Map<String, Object> preview = call("/api/v1/forms/" + id + "/preview", HttpMethod.GET, lawyer, null, Map.class)
                .getBody();
        Map<String, Object> text = call("/api/v1/forms/" + id + "/text", HttpMethod.GET, lawyer, null, Map.class)
                .getBody();

        assertThat((String) preview.get("preview"))
                .contains("ООО «Тестовый оператор»")
                .contains("Травин Иван Сергеевич");
        assertThat((String) text.get("text"))
                .contains("ООО «Тестовый оператор»")
                .doesNotContain("Травин");
        assertThat((String) text.get("checksum")).startsWith("sha256:");
    }

    /**
     * FR-3.1: список форм отдаётся вне транзакции, поэтому краткая строка не имеет права читать пункты —
     * иначе каталог отвечает 500 на ленивой коллекции.
     */
    @Test
    void catalog_list_is_served_outside_a_transaction() {
        String code = "LIST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        createDraft(code, "Форма для списка");

        ResponseEntity<Map> response = call("/api/v1/forms?size=50", HttpMethod.GET, lawyer, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) response.getBody().get("content")).isNotEmpty();
    }

    /**
     * FR-3.1: фильтр по источнику применения обязан выполняться запросом.
     *
     * <p>Раньше он применялся к уже выбранной странице: подходящие формы с других страниц не показывались,
     * а `totalElements` возвращался без учёта фильтра, из-за чего врало число страниц.
     */
    @Test
    void source_filter_is_applied_by_the_query_and_not_to_the_fetched_page() {
        var used = forms.list(
                new ConsentFormService.FormFilter(null, ConsentSource.WEBSITE_APPLICATION, null, null, null),
                PageRequest.of(0, 5));
        assertThat(used.getTotalElements()).isPositive();
        assertThat(used.getContent())
                .allSatisfy(form -> assertThat(form.getSourceChannels()).contains(ConsentSource.WEBSITE_APPLICATION));

        // Источник, которым не пользуется ни одна форма: счётчик обязан быть нулевым, а не общим числом форм.
        var unused = forms.list(
                new ConsentFormService.FormFilter(null, ConsentSource.CALL_CENTER, null, null, null),
                PageRequest.of(0, 5));
        assertThat(unused.getTotalElements()).isZero();
        assertThat(unused.getContent()).isEmpty();
    }
}
