package ru.example.inconsensu.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ru.example.inconsensu.common.application.CryptoService;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.registry.application.ContactMaintenanceService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;

/**
 * NFR-3: при включённом флаге контакты лежат в базе зашифрованными, но поиск и карточка работают как прежде.
 */
@TestPropertySource(
        properties = {
            "inconsensu.crypto.enabled=true",
            // Ключ теста вымышленный и используется только здесь; в эксплуатации он приходит из окружения.
            "inconsensu.crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class ContactEncryptionIT extends AbstractIntegrationTest {

    @Autowired
    private SubjectService subjects;

    @Autowired
    private ContactMaintenanceService maintenance;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CryptoService crypto;

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

    /**
     * Возврат базы в исходное состояние.
     *
     * <p>Проход перешифрования затрагивает все контакты общей базы, а не только созданные здесь. Классы,
     * идущие следом, работают с выключенным флагом и на зашифрованном значении падают в конвертере — так
     * ломался DemoDataIT, когда порядок классов на Linux оказался иным, чем на macOS.
     */
    @AfterEach
    void restore_plaintext_contacts() {
        List<Object[]> restored = jdbc
                .query(
                        "select id, value from subject_contact where value like ?",
                        (row, index) -> new Object[] {row.getObject("id"), row.getString("value")},
                        CryptoService.PREFIX + "%")
                .stream()
                .map(row -> new Object[] {crypto.decrypt((String) row[1]), row[0]})
                .toList();
        jdbc.batchUpdate("update subject_contact set value = ?, search_hmac = null where id = ?", restored);
    }
}
