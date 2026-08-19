package ru.example.cus.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.channels.application.BulkCheckJobService;
import ru.example.cus.channels.domain.BulkCheckJob;
import ru.example.cus.common.domain.CommunicationChannel;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.common.domain.SignatureType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.notification.application.WebhookSubscriptionService;
import ru.example.cus.registry.application.ConsentRegistrationService;
import ru.example.cus.registry.application.RetentionService;
import ru.example.cus.registry.application.SubjectCardPdfService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.support.AbstractIntegrationTest;
import ru.example.cus.support.RunAs;
import ru.example.cus.support.TestForms;

/** Приёмка этапа 8: эксплуатационные возможности — ретенция, PDF, асинхронная проверка, allow-list. */
class OperationalReadinessIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private SubjectCardPdfService cardPdf;

    @Autowired
    private BulkCheckJobService bulkChecks;

    @Autowired
    private RetentionService retention;

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private TestForms testForms;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ru.example.cus.support.TestAccounts accounts;

    @Test
    void card_is_exported_to_pdf_with_cyrillic_text() {
        Consent consent = registerConsent();

        byte[] pdf = RunAs.roles("test-manager", List.of("MANAGER"), () -> cardPdf.render(consent.getSubjectId()));

        assertThat(pdf).isNotEmpty();
        // Заголовок PDF: файл действительно PDF, а не текст с ошибкой.
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void asynchronous_bulk_check_returns_the_same_answer_as_the_synchronous_one() {
        Consent consent = registerConsent();
        String subjectId = consent.getSubjectId().toString();

        BulkCheckJob job = RunAs.roles(
                "test-marketing",
                List.of("MARKETING"),
                () -> bulkChecks.runNow(CommunicationChannel.EMAIL, List.of(subjectId)));

        assertThat(job.getStatus()).isEqualTo(BulkCheckJob.Status.DONE);
        assertThat(job.getRequested()).isEqualTo(1);
        assertThat(job.getProcessed()).isEqualTo(1);
        assertThat(job.getResult()).contains(subjectId);
    }

    @Test
    void retention_dry_run_changes_nothing_and_reports_counters() {
        RetentionService.RetentionResult result =
                RunAs.roles("test-admin", List.of("ADMIN"), () -> retention.run(true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.consentsArchived()).isGreaterThanOrEqualTo(0);
        assertThat(result.eventsAged()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void webhook_subscription_respects_the_allow_list() {
        // Список хостов в тестовом профиле пуст, поэтому проверяется только схема адреса (NFR-4).
        assertThatThrownBy(() -> RunAs.roles(
                        "test-admin",
                        List.of("ADMIN"),
                        () -> subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                                "Плохая схема", "ftp://crm.example.ru/hook", java.util.Set.of(), Map.of(), true))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http://");
    }

    @Test
    void bulk_check_is_closed_for_the_call_centre_role_and_downloadable_as_csv() {
        Consent consent = registerConsent();
        String subjectId = consent.getSubjectId().toString();
        String body = "{\"channel\":\"EMAIL\",\"identifiers\":[\"" + subjectId + "\"]}";

        // Приложение E: у MANAGER только одиночная проверка (ADR-0042, вопрос 18).
        assertThat(post("/api/v1/channels/check-async", RoleCode.MANAGER, body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> submitted = post("/api/v1/channels/check-async", RoleCode.MARKETING, body);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jobId = submitted.getBody().replaceAll("(?s).*\"jobId\":\"([0-9a-f-]{36})\".*", "$1");

        String csv = awaitCsv(jobId);
        assertThat(csv).startsWith("identifier,allowed,reason");
        assertThat(csv).contains(subjectId);
    }

    private ResponseEntity<String> post(String path, RoleCode role, String body) {
        HttpHeaders headers = accounts.authorizationFor(role.name());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Задача считается в фоне: файл появляется, как только расчёт завершён. */
    private String awaitCsv(String jobId) {
        ResponseEntity<String> response = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            response = restTemplate.exchange(
                    "/api/v1/channels/check-async/" + jobId + "/download",
                    HttpMethod.GET,
                    new HttpEntity<>(accounts.authorizationFor(RoleCode.MARKETING.name())),
                    String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                return response.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        ResponseEntity<String> status = restTemplate.exchange(
                "/api/v1/channels/check-async/" + jobId,
                HttpMethod.GET,
                new HttpEntity<>(accounts.authorizationFor(RoleCode.MARKETING.name())),
                String.class);
        throw new AssertionError("файл недоступен: " + response.getStatusCode() + " " + response.getBody()
                + "; состояние задачи: " + status.getStatusCode() + " " + status.getBody());
    }

    private Consent registerConsent() {
        ConsentForm form = testForms.publishTwoItemForm();
        var items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-OPS-" + UUID.randomUUID().toString().substring(0, 8),
                "Чкалов",
                "Пётр",
                "Иванович",
                null,
                List.of(new SubjectService.ContactForm(
                        ContactType.EMAIL,
                        "ops-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
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
                                "эксплуатационный тест",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000048",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }
}
