package ru.example.inconsensu.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.example.inconsensu.common.domain.ConsentCategory;

/**
 * Проверка обязательных реквизитов согласия по ч. 4 ст. 9 152-ФЗ и блокирующих правил FR-1.4.
 *
 * <p>Система не заменяет юриста (§1): она проверяет наличие реквизитов, а не качество формулировок. Всё, что
 * нельзя проверить машинально, остаётся предупреждением и не блокирует отправку на согласование.
 */
public final class FormRequisitesValidator {

    /** Плейсхолдер, которым форма идентифицирует субъекта; без него нет блока идентификации. */
    public static final String SUBJECT_NAME_PLACEHOLDER = "subject.fio";

    private static final Set<String> SUBJECT_CONTACT_PLACEHOLDERS = Set.of("subject.phone", "subject.email");
    private static final String NOT_FILLED = "не заполнено";

    private FormRequisitesValidator() {}

    public static FormValidationResult validate(FormValidationInput input) {
        List<FormValidationResult.Finding> violations = new ArrayList<>();
        List<FormValidationResult.Finding> warnings = new ArrayList<>();
        List<FormValidationResult.Requisite> checklist = new ArrayList<>();

        // FR-1.3: плейсхолдер {{third_party.*}} однозначен, только когда у формы один получатель.
        // Форма с передачами двум партнёрам подставила бы во все вхождения реквизиты первого — клиент
        // получил бы документ, называющий не того, кому уходят его данные.
        long distinctThirdParties = input.items().stream()
                .map(FormValidationInput.Item::thirdPartyName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .count();
        boolean bodyMentionsThirdParty = input.body() != null && input.body().contains("{{third_party.");
        if (bodyMentionsThirdParty && distinctThirdParties != 1) {
            violations.add(
                    FormValidationResult.Finding.form(
                            "third-party-placeholder-ambiguous",
                            distinctThirdParties == 0
                                    ? "В тексте есть {{third_party.*}}, но ни один пункт не указывает третье лицо"
                                    : "В тексте есть {{third_party.*}}, а получателей несколько: назовите их в формулировках пунктов"));
        }

        boolean operatorFilled = filled(input.operatorName()) && filled(input.operatorAddress());
        checklist.add(new FormValidationResult.Requisite("operator", "Наименование и адрес оператора", operatorFilled));
        if (!operatorFilled) {
            violations.add(FormValidationResult.Finding.form(
                    "operator-requisites-missing",
                    "Не заполнены наименование или адрес оператора: укажите их в настройках оператора"));
        }

        boolean hasItems = input.items() != null && !input.items().isEmpty();
        checklist.add(new FormValidationResult.Requisite("items", "Форма содержит хотя бы один пункт", hasItems));
        if (!hasItems) {
            violations.add(FormValidationResult.Finding.form("no-items", "Форма не содержит ни одного пункта"));
            return new FormValidationResult(violations, warnings, checklist);
        }

        List<FormValidationInput.Item> items = input.items();

        boolean purposesEverywhere = items.stream().allMatch(item -> notEmpty(item.purposes()));
        checklist.add(
                new FormValidationResult.Requisite("purposes", "Цель обработки у каждого пункта", purposesEverywhere));

        boolean categoriesEverywhere = items.stream().allMatch(item -> notEmpty(item.pdnCategories()));
        checklist.add(new FormValidationResult.Requisite(
                "pdn-categories", "Перечень персональных данных у каждого пункта", categoriesEverywhere));

        boolean actionsFilled = filled(input.processingActions());
        checklist.add(new FormValidationResult.Requisite(
                "processing-actions", "Перечень действий и способов обработки", actionsFilled));
        if (!actionsFilled) {
            violations.add(FormValidationResult.Finding.form(
                    "processing-actions-missing",
                    "Не заполнен перечень действий и способов обработки персональных данных"));
        }

        boolean revocationFilled = filled(input.revocationProcedure());
        checklist.add(
                new FormValidationResult.Requisite("revocation", "Срок действия и порядок отзыва", revocationFilled));
        if (!revocationFilled) {
            violations.add(FormValidationResult.Finding.form(
                    "revocation-procedure-missing", "Не заполнены срок действия согласия и порядок его отзыва"));
        }

        Set<String> placeholders = FormRenderer.placeholdersIn(input.body());
        boolean identificationPresent = placeholders.contains(SUBJECT_NAME_PLACEHOLDER)
                && placeholders.stream().anyMatch(SUBJECT_CONTACT_PLACEHOLDERS::contains);
        checklist.add(new FormValidationResult.Requisite(
                "subject-identification", "Блок идентификации и подписи субъекта", identificationPresent));
        if (!identificationPresent) {
            violations.add(
                    FormValidationResult.Finding.form(
                            "subject-identification-missing",
                            "В тексте формы нет блока идентификации субъекта: добавьте {{subject.fio}} и хотя бы один контакт"));
        }

        boolean transfersDescribed = true;
        for (int index = 0; index < items.size(); index++) {
            FormValidationInput.Item item = items.get(index);
            int number = index + 1;

            if (!notEmpty(item.purposes())) {
                violations.add(FormValidationResult.Finding.item(
                        "purpose-missing", "Пункт " + number + ": не указана цель обработки", number));
            }
            if (!notEmpty(item.pdnCategories())) {
                violations.add(FormValidationResult.Finding.item(
                        "pdn-categories-missing",
                        "Пункт " + number + ": не указан перечень персональных данных",
                        number));
            }
            if (!filled(item.text())) {
                violations.add(FormValidationResult.Finding.item(
                        "item-text-missing", "Пункт " + number + ": не заполнена формулировка", number));
            }
            if (!item.typeActive()) {
                violations.add(FormValidationResult.Finding.item(
                        "inactive-type",
                        "Пункт " + number + ": тип согласия «" + item.typeNameRu() + "» деактивирован (FR-1.1)",
                        number));
            }

            // FR-1.4: пункт с requires_third_party без третьего лица не допускается.
            if (item.typeRequiresThirdParty()) {
                if (!filled(item.thirdPartyName()) || !filled(item.thirdPartyAddress())) {
                    transfersDescribed = false;
                    violations.add(FormValidationResult.Finding.item(
                            "third-party-missing",
                            "Пункт " + number
                                    + ": для передачи данных нужно указать третье лицо с наименованием и адресом",
                            number));
                }
            }

            // FR-1.4, предупреждение: рекламный пункт не может быть условием заключения договора (ч. 4 ст. 9).
            if (item.category() == ConsentCategory.ADVERTISING && item.mandatory()) {
                warnings.add(FormValidationResult.Finding.item(
                        "advertising-mandatory",
                        "Пункт " + number + ": рекламное согласие помечено обязательным для заключения договора",
                        number));
            }

            // FR-1.4, предупреждение: специальные категории лучше выносить в отдельный пункт.
            if (item.specialPdnCategories() && items.size() > 1) {
                warnings.add(FormValidationResult.Finding.item(
                        "special-categories-mixed",
                        "Пункт " + number + ": специальные категории персональных данных включены в общий пункт",
                        number));
            }
        }

        checklist.add(new FormValidationResult.Requisite(
                "third-party", "Наименование и адрес третьего лица для передач", transfersDescribed));

        // FR-1.4: согласие на распространение оформляется отдельно (ст. 10.1).
        boolean hasDistribution = items.stream().anyMatch(item -> item.category() == ConsentCategory.DISTRIBUTION);
        if (hasDistribution && items.size() > 1) {
            violations.add(FormValidationResult.Finding.form(
                    "distribution-not-alone",
                    "Согласие на распространение персональных данных оформляется отдельной формой "
                            + "и не может соседствовать с другими пунктами (ст. 10.1 152-ФЗ)"));
        }

        return new FormValidationResult(violations, warnings, checklist);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank() && !NOT_FILLED.equalsIgnoreCase(value.trim());
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && values.stream().anyMatch(FormRequisitesValidator::filled);
    }
}
