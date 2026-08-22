package ru.example.inconsensu.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RetentionService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestForms;

/**
 * NFR-5 и §8: политика хранения не оживляет заменённые согласия.
 *
 * <p>Ретенция переносила в архив отозванное согласие, а ссылку предшественника на него просто обнуляла —
 * иначе мешал внешний ключ. Выборка действующих согласий отбирает строки по `superseded_by_id is null`,
 * поэтому заменённое согласие после ночного прогона снова становилось действующим, и по нему опять было
 * «можно» писать клиенту. §8 при этом прямо запрещает править экземпляры согласий.
 */
class RetentionChainIT extends AbstractIntegrationTest {

    @Autowired
    private ConsentRegistrationService registration;

    @Autowired
    private ConsentQueryService consents;

    @Autowired
    private RevocationService revocation;

    @Autowired
    private RetentionService retention;

    @Autowired
    private TestForms testForms;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_superseded_consent_does_not_come_back_to_life_after_the_retention_run() {
        ConsentForm form = testForms.publishTwoItemForm();
        ConsentFormItem item = itemOf(form, "ADVERTISING_EMAIL");

        Consent first = register(form, item, null);
        UUID subjectId = first.getSubjectId();
        // Второе согласие того же типа заменяет первое (FR-4.3).
        Consent second = register(form, item, subjectId);

        assertThat(consents.currentConsentsOf(subjectId))
                .as("после замены действующим остаётся только новое согласие")
                .filteredOn(view -> view.consent().getId().equals(first.getId()))
                .allSatisfy(view -> assertThat(view.consent().isEffective()).isFalse());

        // Преемник отозван, и отзыв давний — по политике хранения он подлежит переносу в архив.
        RunAs.rolesVoid(
                "test-manager",
                List.of("MANAGER"),
                () -> revocation.revoke(
                        second.getId(),
                        "Клиент отозвал",
                        RevocationSource.CALL_CENTER,
                        "ОБР-РЕТЕНЦИЯ",
                        Map.<String, Object>of()));
        jdbc.update(
                "update consent set revoked_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3650, ChronoUnit.DAYS)),
                second.getId());

        RunAs.rolesVoid("test-admin", List.of("ADMIN"), () -> retention.run(false));

        Long supersededBy = jdbc.queryForObject(
                "select count(*) from consent where id = ? and superseded_by_id is not null",
                Long.class,
                first.getId());
        assertThat(supersededBy)
                .as("ссылка на заменившее согласие не должна обнуляться: §8 запрещает править экземпляры")
                .isEqualTo(1);

        assertThat(consents.effectiveConsentsOf(subjectId))
                .as("заменённое согласие не должно снова стать действующим")
                .noneSatisfy(view -> assertThat(view.consent().getId()).isEqualTo(first.getId()));
    }

    private static ConsentFormItem itemOf(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(candidate -> candidate.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow();
    }

    /** @param subjectId клиент, которому регистрируется согласие; {@code null} — завести нового */
    private Consent register(ConsentForm form, ConsentFormItem item, UUID subjectId) {
        String externalId = subjectId == null
                ? "CRM-RET-" + UUID.randomUUID().toString().substring(0, 8)
                : jdbc.queryForObject("select external_id from subject where id = ?", String.class, subjectId);

        return registration
                .register(
                        UUID.randomUUID().toString(),
                        new ConsentRegistrationService.RegistrationRequest(
                                null,
                                new SubjectService.SubjectForm(
                                        externalId,
                                        "Чкалов",
                                        "Пётр",
                                        "Иванович",
                                        null,
                                        List.of(new SubjectService.ContactForm(
                                                ContactType.EMAIL,
                                                "ret-"
                                                        + UUID.randomUUID()
                                                                .toString()
                                                                .substring(0, 6) + "@example.ru",
                                                true))),
                                form.getId(),
                                List.of(new ConsentRegistrationService.ItemDecision(item.getId(), true)),
                                Instant.now(),
                                ConsentSource.WEBSITE_APPLICATION,
                                "проверка ретенции",
                                SignatureType.SIMPLE_ES_SMS,
                                Map.of(
                                        "phone", "+79160000052",
                                        "otpVerifiedAt", "2026-08-18T09:00:00Z",
                                        "otpHash", "hash",
                                        "ip", "10.0.0.1",
                                        "userAgent", "Mozilla")))
                .created()
                .get(0);
    }
}
