package ru.example.cus.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.cus.catalog.domain.FormRenderer;

/** FR-1.6: контрольная сумма описывает документ, а не подписавшего его человека. */
class FormRendererTest {

    private static final String BODY =
            "Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}} ({{operator.address}}).";

    private static final Map<String, String> OPERATOR =
            FormRenderer.operatorValues("ООО «Оператор»", "Москва, ул. Тестовая, 1");

    @Test
    void render_substitutes_every_known_placeholder() {
        Map<String, String> values = new java.util.LinkedHashMap<>(OPERATOR);
        values.put("subject.fio", "Травин Иван Сергеевич");
        values.put("subject.phone", "+7 (916) 000-00-41");

        String rendered = FormRenderer.render(BODY, values);

        assertThat(rendered)
                .contains("Травин Иван Сергеевич")
                .contains("ООО «Оператор»")
                .doesNotContain("{{");
    }

    @Test
    void canonical_render_hides_subject_data_but_keeps_operator_requisites() {
        String canonical = FormRenderer.renderCanonical(BODY, OPERATOR);

        assertThat(canonical)
                .contains("ООО «Оператор»")
                .contains(FormRenderer.SUBJECT_PLACEHOLDER_FILLER)
                .doesNotContain("{{subject");
    }

    @Test
    void checksum_is_the_same_for_two_different_subjects() {
        // Иначе §8.5 («согласие хранит снимок условий») превратился бы в снимок конкретного человека.
        String first = FormRenderer.checksum(FormRenderer.renderCanonical(BODY, OPERATOR));
        String second = FormRenderer.checksum(FormRenderer.renderCanonical(BODY, OPERATOR));

        assertThat(first)
                .isEqualTo(second)
                .startsWith(FormRenderer.CHECKSUM_PREFIX)
                .hasSize(71);
    }

    @Test
    void checksum_changes_when_the_text_changes() {
        String original = FormRenderer.checksum(FormRenderer.renderCanonical(BODY, OPERATOR));
        String edited = FormRenderer.checksum(FormRenderer.renderCanonical(BODY + " Дополнение.", OPERATOR));

        assertThat(edited).isNotEqualTo(original);
    }

    @Test
    void checksum_ignores_trailing_whitespace_and_line_endings() {
        String unix = FormRenderer.checksum(FormRenderer.renderCanonical("Строка\nВторая", OPERATOR));
        String windows = FormRenderer.checksum(FormRenderer.renderCanonical("Строка   \r\nВторая\r\n  ", OPERATOR));

        assertThat(windows).isEqualTo(unix);
    }

    @Test
    void placeholders_are_discovered_for_the_constructor_panel() {
        assertThat(FormRenderer.placeholdersIn(BODY))
                .containsExactly("subject.fio", "subject.phone", "operator.name", "operator.address");
    }

    @Test
    void unknown_placeholder_becomes_empty_rather_than_leaking_its_name() {
        assertThat(FormRenderer.render("A{{unknown.thing}}B", Map.of())).isEqualTo("AB");
    }
}
