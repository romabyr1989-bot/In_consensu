package ru.example.inconsensu.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.WebhookStub;

/** §9, Приложение E: правилами уведомлений управляют ADMIN и DPO, подписками — только ADMIN. */
class NotificationApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    private static Map<String, Object> ruleBody() {
        return Map.of(
                "name", "Правило " + UUID.randomUUID().toString().substring(0, 8),
                "triggerType", "EXPIRING",
                "daysBefore", List.of(30, 7),
                "recipientEmails", List.of("dpo@example.ru"),
                "channels", List.of("EMAIL"));
    }

    private HttpEntity<Object> as(String role, Object body) {
        HttpHeaders headers = accounts.authorizationFor(role);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void dpo_manages_notification_rules() {
        ResponseEntity<Map> created = restTemplate.exchange(
                "/api/v1/notification-rules", HttpMethod.POST, as(RoleCode.DPO.name(), ruleBody()), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("triggerTypeRu", "заканчивается срок согласия");

        String id = created.getBody().get("id").toString();
        ResponseEntity<Map> deactivated = restTemplate.exchange(
                "/api/v1/notification-rules/" + id + "/deactivate",
                HttpMethod.POST,
                as(RoleCode.DPO.name(), null),
                Map.class);
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivated.getBody()).containsEntry("active", false);
    }

    @Test
    void rule_without_recipients_is_rejected_with_a_readable_message() {
        Map<String, Object> body = Map.of(
                "name",
                "Без получателей",
                "triggerType",
                "EXPIRING",
                "daysBefore",
                List.of(30),
                "channels",
                List.of("EMAIL"));

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/notification-rules", HttpMethod.POST, as(RoleCode.DPO.name(), body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").toString()).contains("адреса или роли");
    }

    @Test
    void marketing_cannot_touch_notification_rules() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/notification-rules", HttpMethod.POST, as(RoleCode.MARKETING.name(), ruleBody()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void subscription_secret_is_returned_once_and_never_listed() {
        try (WebhookStub stub = new WebhookStub(200)) {
            Map<String, Object> body = Map.of(
                    "name", "CRM " + UUID.randomUUID().toString().substring(0, 8),
                    "url", stub.url(),
                    "eventTypes", List.of("consent.revoked"));

            ResponseEntity<Map> created = restTemplate.exchange(
                    "/api/v1/webhooks", HttpMethod.POST, as(RoleCode.ADMIN.name(), body), Map.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody().get("secret").toString()).isNotBlank();

            String id = ((Map<?, ?>) created.getBody().get("subscription"))
                    .get("id")
                    .toString();
            ResponseEntity<String> fetched = restTemplate.exchange(
                    "/api/v1/webhooks/" + id, HttpMethod.GET, as(RoleCode.ADMIN.name(), null), String.class);
            assertThat(fetched.getBody())
                    .doesNotContain(created.getBody().get("secret").toString());

            ResponseEntity<Map> test = restTemplate.exchange(
                    "/api/v1/webhooks/" + id + "/test", HttpMethod.POST, as(RoleCode.ADMIN.name(), null), Map.class);
            assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(test.getBody()).containsEntry("successful", true);

            ResponseEntity<Map> deliveries = restTemplate.exchange(
                    "/api/v1/webhooks/" + id + "/deliveries",
                    HttpMethod.GET,
                    as(RoleCode.ADMIN.name(), null),
                    Map.class);
            assertThat((List<?>) deliveries.getBody().get("content")).hasSize(1);
        }
    }

    @Test
    void invalid_subscription_url_is_rejected() {
        Map<String, Object> body = Map.of("name", "Плохой адрес", "url", "ftp://crm.example.ru/hook");

        ResponseEntity<Map> response =
                restTemplate.exchange("/api/v1/webhooks", HttpMethod.POST, as(RoleCode.ADMIN.name(), body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").toString()).contains("http://");
    }

    @Test
    void test_email_reports_smtp_failure_instead_of_throwing() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/notifications/test-email",
                HttpMethod.POST,
                as(RoleCode.ADMIN.name(), Map.of("email", "dpo@example.ru")),
                Map.class);

        // В тестовом профиле отправка выключена: эндпоинт обязан вернуть причину, а не 500.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sent", false);
        assertThat(response.getBody().get("error").toString()).isNotBlank();
    }
}
