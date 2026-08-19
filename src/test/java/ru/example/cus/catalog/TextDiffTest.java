package ru.example.cus.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.example.cus.catalog.domain.TextDiff;

/** FR-3.2 (этап 8): текстовый diff двух версий формы. */
class TextDiffTest {

    @Test
    void identical_texts_have_no_changes() {
        List<TextDiff.Line> diff = TextDiff.compare("первая\nвторая", "первая\nвторая");

        assertThat(TextDiff.hasChanges(diff)).isFalse();
        assertThat(diff).allSatisfy(line -> assertThat(line.kind()).isEqualTo(TextDiff.Kind.SAME));
    }

    @Test
    void added_and_removed_lines_are_marked() {
        List<TextDiff.Line> diff = TextDiff.compare("первая\nвторая\nтретья", "первая\nтретья\nчетвёртая");

        assertThat(TextDiff.hasChanges(diff)).isTrue();
        assertThat(diff)
                .filteredOn(line -> line.kind() == TextDiff.Kind.REMOVED)
                .extracting(TextDiff.Line::text)
                .containsExactly("вторая");
        assertThat(diff)
                .filteredOn(line -> line.kind() == TextDiff.Kind.ADDED)
                .extracting(TextDiff.Line::text)
                .containsExactly("четвёртая");
    }

    @Test
    void unchanged_lines_keep_their_order() {
        List<TextDiff.Line> diff = TextDiff.compare("а\nб", "а\nв\nб");

        assertThat(diff).extracting(TextDiff.Line::text).containsExactly("а", "в", "б");
    }

    @Test
    void empty_before_marks_everything_as_added() {
        List<TextDiff.Line> diff = TextDiff.compare(null, "новая версия");

        assertThat(diff).singleElement().satisfies(line -> {
            assertThat(line.kind()).isEqualTo(TextDiff.Kind.ADDED);
            assertThat(line.text()).isEqualTo("новая версия");
        });
    }
}
