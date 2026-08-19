package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.TestAccounts;

/** Этап 1: субъекты и поиск по четырём типам запроса (FR-5.2), маскирование контактов (FR-5.1). */
class SubjectRegistryIT extends AbstractIntegrationTest {

    @Autowired
    private SubjectService service;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAccounts accounts;

    /** Фамилия уникальна на прогон: в общей тестовой базе живут однофамильцы из других сценариев. */
    private String uniqueSurname() {
        return "Травин" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private String uniquePhone() {
        // Телефон уникален на прогон: тестовые сценарии делят один номер, и поиск нашёл бы чужих субъектов.
        long tail = Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000L);
        return "+7 916 " + String.format("%07d", tail);
    }

    private String uniqueEmail() {
        return "travin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
    }

    private String phone;
    private String email;

    private Subject travin() {
        String externalId = "CRM-" + UUID.randomUUID().toString().substring(0, 8);
        phone = uniquePhone();
        email = uniqueEmail();
        return service.upsert(new SubjectService.SubjectForm(
                externalId,
                uniqueSurname(),
                "Иван",
                "Сергеевич",
                LocalDate.of(1985, 4, 12),
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, phone, true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL, email.toUpperCase(java.util.Locale.ROOT), true))));
    }

    @Test
    void subject_is_created_with_normalized_contacts() {
        Subject subject = travin();

        assertThat(subject.getFullName()).endsWith("Иван Сергеевич");
        assertThat(subject.getContacts()).hasSize(2);
        assertThat(subject.getContacts())
                .extracting(contact -> contact.getValueNormalized())
                .containsExactlyInAnyOrder(phone.replaceAll("\\D", "").replaceFirst("^7", "+7"), email);
    }

    @Test
    void upsert_updates_the_existing_subject_instead_of_duplicating_it() {
        Subject first = travin();

        Subject second = service.upsert(new SubjectService.SubjectForm(
                first.getExternalId(),
                "Травина",
                "Ирина",
                null,
                null,
                List.of(new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-42", true))));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFullName()).isEqualTo("Травина Ирина");
        assertThat(second.getContacts()).hasSize(1);
    }

    @Test
    void search_finds_the_subject_by_each_of_the_four_query_kinds() {
        Subject subject = travin();
        var page = PageRequest.of(0, 20);

        assertThat(service.search(subject.getExternalId(), page).getContent())
                .extracting(Subject::getId)
                .containsExactly(subject.getId());
        assertThat(service.search(phone, page).getContent())
                .extracting(Subject::getId)
                .containsExactly(subject.getId());
        assertThat(service.search(email.toUpperCase(java.util.Locale.ROOT), page)
                        .getContent())
                .extracting(Subject::getId)
                .containsExactly(subject.getId());
        assertThat(service.search(subject.getLastName() + " Иван", page).getContent())
                .extracting(Subject::getId)
                .containsExactly(subject.getId());
    }

    @Test
    void name_query_shorter_than_three_characters_is_rejected() {
        assertThatThrownBy(() -> service.search("Тр", PageRequest.of(0, 20)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("не менее 3");
    }

    @Test
    void contacts_are_masked_for_roles_without_the_right_to_personal_data() {
        Subject subject = travin();

        String marketing = body("/api/v1/subjects/" + subject.getId(), RoleCode.MARKETING);
        assertThat(marketing).contains("+7 9** ***-**-").doesNotContain(phone);

        String manager = body("/api/v1/subjects/" + subject.getId(), RoleCode.MANAGER);
        assertThat(manager).contains(phone);
    }

    @Test
    void creating_subjects_is_closed_for_reading_roles() {
        String payload =
                """
                {"externalId":"CRM-forbidden","lastName":"Тест","firstName":"Тест","contacts":[]}
                """;
        var headers = accounts.authorizationFor(RoleCode.MARKETING.name());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/subjects", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * FR-5.2, UI-3: ответ поиска собирается уже вне транзакции. Контакты — ленивая коллекция, поэтому
     * проверка идёт именно по HTTP: вызов сервиса внутри транзакции такую ошибку не поймает.
     */
    @Test
    void search_over_http_returns_contacts_and_not_an_error() {
        Subject subject = travin();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/subjects?query=" + subject.getExternalId(),
                HttpMethod.GET,
                new HttpEntity<>(accounts.authorizationFor(RoleCode.MANAGER.name())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(subject.getExternalId()).contains(phone);
    }

    private String body(String path, RoleCode role) {
        return restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(accounts.authorizationFor(role.name())), String.class)
                .getBody();
    }
}
