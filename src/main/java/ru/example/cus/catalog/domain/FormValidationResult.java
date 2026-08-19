package ru.example.cus.catalog.domain;

import java.util.List;

/**
 * Итог проверки формы на обязательные реквизиты (FR-1.3, FR-1.4).
 *
 * <p>Валидатор возвращает полный список нарушений, а не первое найденное: юрист должен увидеть всё сразу, а не
 * узнавать о проблемах по одной (FR-1.3).
 */
public record FormValidationResult(List<Finding> violations, List<Finding> warnings, List<Requisite> checklist) {

    /** Нарушение или предупреждение. {@code itemRef} — номер пункта формы, если проблема в нём. */
    public record Finding(String code, String messageRu, Integer itemNumber) {

        public static Finding form(String code, String messageRu) {
            return new Finding(code, messageRu, null);
        }

        public static Finding item(String code, String messageRu, int itemNumber) {
            return new Finding(code, messageRu, itemNumber);
        }
    }

    /** Строка чек-листа реквизитов для панели конструктора (UI-8). */
    public record Requisite(String code, String nameRu, boolean satisfied) {}

    public boolean valid() {
        return violations.isEmpty();
    }
}
