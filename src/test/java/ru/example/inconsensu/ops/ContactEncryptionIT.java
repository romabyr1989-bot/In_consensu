package ru.example.inconsensu.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.registry.application.ContactMaintenanceService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;

/**
 * NFR-3: при включённом флаге контакты лежат в базе зашифрованными, но поиск и карточка работают как прежде.
 */
@TestPropertySource(
        properties = {
            "inconsensu.crypto.enabled=true",
            // Ключ теста вымышленный и используется только здесь; в эксплуатации он приходит из окружения.
            "inconsensu.crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
@Import({TestAccounts.class, TestForms.class})
class ContactEncryptionIT {

    /**
     * Собственная база, а не общий контейнер остальных тестов.
     *
     * <p>Класс включает шифрование, а проход ротации ключа переписывает все контакты базы, какие в ней
     * есть. На общей базе это утекало в соседние классы: они работают с выключенным флагом и падали в
     * конвертере на чужом шифртексте. Точечная уборка после теста от этого не спасает — режим хранения
     * глобален, поэтому изолируется хранилище, а не строки.
     */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cus")
            .withUsername("cus")
            .withPassword("cus");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SubjectService subjects;

    @Autowired
    private ContactMaintenanceService maintenance;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contact_is_stored_encrypted_and_still_searchable() {
        String phone = "+7 916 000-01-" + (10 + (int) (Math.random() * 80));
        String externalId = "CRM-ENC-" + UUID.randomUUID().toString().substring(0, 8);

        Subject saved = RunAs.roles(
                "test-admin",
                List.of("ADMIN"),
                () -> subjects.upsert(new SubjectService.SubjectForm(
                        externalId,
                        "Заозёрная",
                        "Мария",
                        "Олеговна",
                        null,
                        List.of(new SubjectService.ContactForm(ContactType.PHONE, phone, true)))));

        String stored = jdbc.queryForObject(
                "select value from subject_contact where subject_id = ?", String.class, saved.getId());
        String hmac = jdbc.queryForObject(
                "select search_hmac from subject_contact where subject_id = ?", String.class, saved.getId());

        assertThat(stored).startsWith("enc:v1:");
        assertThat(stored).doesNotContain("9160000");
        assertThat(hmac).isNotBlank().doesNotContain("9160000");

        // Чтение расшифровывает прозрачно, поиск идёт по HMAC (FR-5.2).
        Subject loaded = RunAs.roles("test-admin", List.of("ADMIN"), () -> subjects.get(saved.getId()));
        assertThat(loaded.getContacts().get(0).getValue()).isEqualTo(phone.trim());

        var found = RunAs.roles("test-admin", List.of("ADMIN"), () -> subjects.search(phone, PageRequest.of(0, 10)));
        assertThat(found.getContent()).extracting(Subject::getId).contains(saved.getId());
    }

    @Test
    void reencryption_pass_is_idempotent() {
        ContactMaintenanceService.ReencryptResult first =
                RunAs.roles("test-admin", List.of("ADMIN"), maintenance::reencryptAll);
        ContactMaintenanceService.ReencryptResult second =
                RunAs.roles("test-admin", List.of("ADMIN"), maintenance::reencryptAll);

        assertThat(first.encryptionEnabled()).isTrue();
        assertThat(second.processed()).isEqualTo(first.processed());
    }
}
