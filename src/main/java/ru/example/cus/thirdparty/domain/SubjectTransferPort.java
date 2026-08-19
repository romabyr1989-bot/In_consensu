package ru.example.cus.thirdparty.domain;

import java.util.List;
import java.util.UUID;

/**
 * Как модуль третьих лиц получает согласия субъекта на передачу.
 *
 * <p>Инверсия зависимости, как и в модуле каналов: registry уже зависит от thirdparty (регистрация согласия
 * проверяет договор), поэтому обратная зависимость создала бы цикл, запрещённый §5.
 */
public interface SubjectTransferPort {

    /** Текущие согласия субъекта на передачу данных, включая отозванные и истёкшие. */
    List<TransferSnapshots.TransferConsent> transferConsentsOf(UUID subjectId);

    /**
     * Живо ли базовое согласие на обработку ПДн (§8.3 п.3).
     *
     * <p>Без него передавать данные нельзя так же, как нельзя звонить: разрешение на передачу — надстройка
     * над разрешением обрабатывать.
     */
    boolean baseConsentUsable(UUID subjectId);
}
