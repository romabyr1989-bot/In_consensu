package ru.example.inconsensu.catalog.domain;

import java.util.List;
import ru.example.inconsensu.common.domain.ConsentCategory;

/**
 * Всё, что нужно валидатору реквизитов, в виде простых значений.
 *
 * <p>Домен не ходит в репозитории и в настройки: реквизиты оператора и данные третьих лиц подставляет
 * application-слой, поэтому правила ч. 4 ст. 9 152-ФЗ проверяются обычным юнит-тестом без базы (§5, §11).
 */
public record FormValidationInput(
        String operatorName,
        String operatorAddress,
        String body,
        String processingActions,
        String revocationProcedure,
        List<Item> items) {

    /**
     * @param specialPdnCategories есть ли среди категорий пункта специальные или биометрические (ст. 10, 11)
     */
    public record Item(
            String typeCode,
            String typeNameRu,
            ConsentCategory category,
            boolean typeRequiresThirdParty,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            boolean specialPdnCategories,
            boolean mixedPdnCategories,
            String thirdPartyName,
            String thirdPartyAddress,
            boolean mandatory,
            boolean typeActive) {}
}
