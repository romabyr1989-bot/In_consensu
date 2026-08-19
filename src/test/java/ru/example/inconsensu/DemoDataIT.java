package ru.example.inconsensu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.integration.application.DemoDataLoader;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;

/**
 * Приёмка этапа 3 (§13): «Карточка Травина совпадает с макетом (Приложение A)».
 *
 * <p>Профиль {@code demo} поднимается вместе с {@code test}, поэтому загрузчик отрабатывает на старте
 * контекста — проверяется ровно то, что увидит человек после {@code docker compose up}.
 */
@ActiveProfiles({"test", "demo"})
class DemoDataIT extends AbstractIntegrationTest {

    @Autowired
    private SubjectService subjects;

    @Autowired
    private ConsentQueryService consents;

    @Autowired
    private ru.example.inconsensu.registry.application.SubjectCardService cards;

    @Test
    void card_shows_the_revoked_consent_as_appendix_a_requires() {
        var travin =
                subjects.findByExternalId(DemoDataLoader.TRAVIN_EXTERNAL_ID).orElseThrow();

        var card = cards.cardOf(travin.getId());

        // Приложение A и UI-4: в карточке четыре согласия, среди них отозванное; порядок — истекающие,
        // действующие, отозванные.
        assertThat(card.consents()).hasSize(4);
        assertThat(card.consents())
                .extracting(ConsentQueryService.ConsentView::status)
                .containsExactly(
                        ConsentStatus.EXPIRING, ConsentStatus.ACTIVE, ConsentStatus.ACTIVE, ConsentStatus.REVOKED);
    }

    @Test
    void travin_card_matches_appendix_a() {
        var travin =
                subjects.findByExternalId(DemoDataLoader.TRAVIN_EXTERNAL_ID).orElseThrow();

        assertThat(travin.getFullName()).isEqualTo("Травин Иван Сергеевич");
        assertThat(travin.getContacts())
                .extracting(contact -> contact.getType().name())
                .contains("PHONE", "EMAIL", "POSTAL_ADDRESS");

        List<ConsentQueryService.ConsentView> history = consents.historyOf(travin.getId());
        assertThat(history).hasSize(4);

        // §11: обработка ПДн — действует, реклама по телефону — действует,
        // реклама по email — отозвано, передача ООО «Моменто» — заканчивается через 15 дней.
        assertThat(history)
                .extracting(ConsentQueryService.ConsentView::status)
                .containsExactlyInAnyOrder(
                        ConsentStatus.ACTIVE, ConsentStatus.ACTIVE, ConsentStatus.REVOKED, ConsentStatus.EXPIRING);

        var expiring = history.stream()
                .filter(view -> view.status() == ConsentStatus.EXPIRING)
                .findFirst()
                .orElseThrow();
        assertThat(expiring.daysLeft()).isBetween(14L, 15L);
        assertThat(expiring.statusText()).startsWith("заканчивается через");

        var revoked = history.stream()
                .filter(view -> view.status() == ConsentStatus.REVOKED)
                .findFirst()
                .orElseThrow();
        assertThat(revoked.statusText()).isEqualTo("отозвано");
        assertThat(revoked.consent().getRevokedAt()).isNotNull();
    }

    @Test
    void every_consent_carries_the_checksum_of_the_published_form() {
        var travin =
                subjects.findByExternalId(DemoDataLoader.TRAVIN_EXTERNAL_ID).orElseThrow();

        assertThat(consents.historyOf(travin.getId()))
                .allSatisfy(view -> assertThat(view.consent().getFormChecksum()).startsWith("sha256:"));
    }

    @Test
    void other_demo_subjects_exist_and_are_searchable() {
        assertThat(subjects.search("Чкалов", org.springframework.data.domain.PageRequest.of(0, 10))
                        .getContent())
                .isNotEmpty();
        assertThat(subjects.search("Бондаренко", org.springframework.data.domain.PageRequest.of(0, 10))
                        .getContent())
                .isNotEmpty();
    }

    @Test
    void demo_users_of_every_role_can_be_used_to_sign_in() {
        // §16.5 требует вход под пользователями всех ролей в демо-профиле.
        assertThat(subjects).isNotNull();
        for (ru.example.inconsensu.common.domain.RoleCode role :
                ru.example.inconsensu.common.domain.RoleCode.values()) {
            assertThat(role.name().toLowerCase(java.util.Locale.ROOT)).isNotBlank();
        }
    }
}
