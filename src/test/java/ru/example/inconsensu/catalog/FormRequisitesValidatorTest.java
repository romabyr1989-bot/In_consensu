package ru.example.inconsensu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.catalog.domain.FormRequisitesValidator;
import ru.example.inconsensu.catalog.domain.FormValidationInput;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.common.domain.ConsentCategory;

/** FR-1.3, FR-1.4: обязательные реквизиты ч. 4 ст. 9 152-ФЗ и блокирующие правила конструктора. */
class FormRequisitesValidatorTest {

    private static final String BODY = "Я, {{subject.fio}}, телефон {{subject.phone}}, даю согласие {{operator.name}}.";

    private static FormValidationInput.Item processingItem() {
        return new FormValidationInput.Item(
                "PDN_PROCESSING",
                "Обработка ПДн",
                ConsentCategory.PROCESSING,
                false,
                "Согласие на обработку персональных данных",
                List.of("рассмотрение заявки"),
                List.of("FIO", "PHONE"),
                false,
                false,
                null,
                null,
                true,
                true);
    }

    private static FormValidationInput valid(List<FormValidationInput.Item> items) {
        return new FormValidationInput(
                "ООО «Оператор»",
                "Москва, ул. Тестовая, 1",
                BODY,
                "сбор, запись, хранение, уничтожение",
                "согласие действует до отзыва; отзыв — в личном кабинете",
                items);
    }

    private static List<String> codes(List<FormValidationResult.Finding> findings) {
        return findings.stream().map(FormValidationResult.Finding::code).toList();
    }

    @Test
    void complete_form_passes() {
        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of(processingItem())));

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.checklist())
                .allSatisfy(requisite -> assertThat(requisite.satisfied()).isTrue());
    }

    @Test
    void missing_operator_requisites_block_submission() {
        FormValidationInput input = new FormValidationInput(
                "не заполнено", "не заполнено", BODY, "действия", "отзыв", List.of(processingItem()));

        FormValidationResult result = FormRequisitesValidator.validate(input);

        assertThat(result.valid()).isFalse();
        assertThat(codes(result.violations())).contains("operator-requisites-missing");
    }

    @Test
    void every_item_needs_a_purpose_and_a_list_of_personal_data() {
        FormValidationInput.Item bare = new FormValidationInput.Item(
                "PDN_PROCESSING",
                "Обработка ПДн",
                ConsentCategory.PROCESSING,
                false,
                "текст",
                List.of(),
                List.of(),
                false,
                false,
                null,
                null,
                false,
                true);

        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of(bare)));

        assertThat(codes(result.violations())).contains("purpose-missing", "pdn-categories-missing");
        assertThat(result.violations())
                .allSatisfy(finding -> assertThat(finding.itemNumber()).isEqualTo(1));
    }

    @Test
    void processing_actions_and_revocation_procedure_are_mandatory() {
        FormValidationInput input =
                new FormValidationInput("ООО «Оператор»", "адрес", BODY, null, "  ", List.of(processingItem()));

        FormValidationResult result = FormRequisitesValidator.validate(input);

        assertThat(codes(result.violations())).contains("processing-actions-missing", "revocation-procedure-missing");
    }

    @Test
    void form_without_subject_identification_block_is_rejected() {
        FormValidationInput input = new FormValidationInput(
                "ООО «Оператор»", "адрес", "Текст без плейсхолдеров", "действия", "отзыв", List.of(processingItem()));

        FormValidationResult result = FormRequisitesValidator.validate(input);

        assertThat(codes(result.violations())).contains("subject-identification-missing");
    }

    @Test
    void transfer_item_without_a_third_party_is_blocked() {
        FormValidationInput.Item transfer = new FormValidationInput.Item(
                "PDN_TRANSFER",
                "Передача ПДн",
                ConsentCategory.TRANSFER,
                true,
                "текст",
                List.of("доставка"),
                List.of("FIO"),
                false,
                false,
                null,
                null,
                false,
                true);

        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of(transfer)));

        assertThat(codes(result.violations())).contains("third-party-missing");
    }

    @Test
    void distribution_consent_must_be_the_only_item_of_its_form() {
        FormValidationInput.Item distribution = new FormValidationInput.Item(
                "PDN_DISTRIBUTION",
                "Распространение",
                ConsentCategory.DISTRIBUTION,
                false,
                "текст",
                List.of("публикация"),
                List.of("FIO"),
                false,
                false,
                null,
                null,
                false,
                true);

        FormValidationResult mixed = FormRequisitesValidator.validate(valid(List.of(processingItem(), distribution)));
        FormValidationResult alone = FormRequisitesValidator.validate(valid(List.of(distribution)));

        assertThat(codes(mixed.violations())).contains("distribution-not-alone");
        assertThat(alone.valid()).isTrue();
    }

    @Test
    void deactivated_type_cannot_be_used_in_a_new_form() {
        FormValidationInput.Item inactive = new FormValidationInput.Item(
                "OLD_TYPE",
                "Старый тип",
                ConsentCategory.OTHER,
                false,
                "текст",
                List.of("цель"),
                List.of("FIO"),
                false,
                false,
                null,
                null,
                false,
                false);

        assertThat(codes(FormRequisitesValidator.validate(valid(List.of(inactive)))
                        .violations()))
                .contains("inactive-type");
    }

    /**
     * FR-1.4: предупреждения не блокируют отправку.
     *
     * <p>Предупреждение о специальных категориях означает «смешаны с обычными внутри одного пункта».
     * Раньше условие смотрело на число пунктов формы и потому срабатывало как раз на правильном
     * оформлении — когда специальные категории вынесены в отдельный, чистый пункт.
     */
    @Test
    void mandatory_advertising_and_mixed_special_categories_are_warnings_not_blockers() {
        FormValidationInput.Item advertising = new FormValidationInput.Item(
                "ADVERTISING_EMAIL",
                "Реклама по email",
                ConsentCategory.ADVERTISING,
                false,
                "текст",
                List.of("информирование"),
                List.of("EMAIL"),
                false,
                false,
                null,
                null,
                true,
                true);
        FormValidationInput.Item mixed = new FormValidationInput.Item(
                "PDN_PROCESSING",
                "Обработка ПДн",
                ConsentCategory.PROCESSING,
                false,
                "текст",
                List.of("цель"),
                List.of("HEALTH", "EMAIL"),
                true,
                true,
                null,
                null,
                false,
                true);

        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of(advertising, mixed)));

        assertThat(result.valid()).isTrue();
        assertThat(codes(result.warnings())).contains("advertising-mandatory", "special-categories-mixed");
    }

    /** Специальные категории, вынесенные в отдельный чистый пункт, — правильное оформление (FR-1.4). */
    @Test
    void special_categories_in_a_dedicated_item_are_not_a_warning() {
        FormValidationInput.Item ordinary = new FormValidationInput.Item(
                "PDN_PROCESSING",
                "Обработка ПДн",
                ConsentCategory.PROCESSING,
                false,
                "текст",
                List.of("рассмотрение заявки"),
                List.of("FIO", "PHONE"),
                false,
                false,
                null,
                null,
                true,
                true);
        FormValidationInput.Item onlySpecial = new FormValidationInput.Item(
                "PDN_PROCESSING",
                "Обработка ПДн",
                ConsentCategory.PROCESSING,
                false,
                "сведения о здоровье",
                List.of("медицинское сопровождение"),
                List.of("HEALTH"),
                true,
                false,
                null,
                null,
                false,
                true);

        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of(ordinary, onlySpecial)));

        assertThat(codes(result.warnings())).doesNotContain("special-categories-mixed");
    }

    @Test
    void empty_form_reports_a_single_clear_violation() {
        FormValidationResult result = FormRequisitesValidator.validate(valid(List.of()));

        assertThat(codes(result.violations())).contains("no-items");
    }

    @Test
    void all_violations_are_reported_at_once_not_just_the_first() {
        FormValidationInput input = new FormValidationInput(
                "не заполнено", "не заполнено", "без плейсхолдеров", null, null, List.of(processingItem()));

        FormValidationResult result = FormRequisitesValidator.validate(input);

        assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(4);
    }
}
