package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * §9, FR-4.1: внешняя система называет версию формы, по которой получила согласие.
 *
 * <p>Вход `POST /api/v1/consents` описан в ТЗ как «субъект, form_id и version, список item_id …», но поля
 * версии в контракте не было, и присланное значение молча игнорировалось. Между показом текста клиенту и
 * приходом ответа оператор мог опубликовать новую редакцию: записать такое согласие «по новой версии»
 * значит потерять доказательство — клиент видел другой текст.
 */
class ConsentRegistrationApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Test
    void a_mismatched_form_version_is_rejected_and_the_matching_one_is_accepted() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem item = form.getItems().get(0);

        ResponseEntity<String> mismatched = register(form, item, form.getVersionNumber() + 7);

        assertThat(mismatched.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatched.getBody()).contains("действует версия " + form.getVersionNumber());

        ResponseEntity<String> matching = register(form, item, form.getVersionNumber());

        assertThat(matching.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Версия необязательна: интеграции, которым она не нужна, работают как раньше. */
    @Test
    void a_request_without_the_version_is_still_accepted() {
        ConsentForm form = testForms.publishTwoItemForm();

        assertThat(register(form, form.getItems().get(0), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> register(ConsentForm form, ConsentFormItem item, Integer version) {
        String versionField = version == null ? "" : "\"formVersion\": %d,".formatted(version);
        String body =
                ("""
                {
                  "subject": {
                    "externalId": "CRM-API-%s",
                    "lastName": "Чкалов",
                    "firstName": "Пётр",
                    "contacts": [{"type": "EMAIL", "value": "api-%s@example.ru", "primary": true}]
                  },
                  "formId": "%s",
                  %s
                  "items": [{"formItemId": "%s", "accepted": true}],
                  "source": "WEBSITE_APPLICATION",
                  "sourceRef": "заявка API",
                  "signatureType": "SIMPLE_ES_SMS",
                  "evidence": {
                    "phone": "+79160000050",
                    "otpVerifiedAt": "2026-08-18T09:00:00Z",
                    "otpHash": "hash",
                    "ip": "10.0.0.1",
                    "userAgent": "Mozilla"
                  }
                }
                """)
                        .formatted(
                                UUID.randomUUID().toString().substring(0, 8),
                                UUID.randomUUID().toString().substring(0, 6),
                                form.getId(),
                                versionField,
                                item.getId());

        HttpHeaders headers = new HttpHeaders(accounts.authorizationFor(RoleCode.INTEGRATION.name()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return restTemplate.exchange(
                "/api/v1/consents", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
