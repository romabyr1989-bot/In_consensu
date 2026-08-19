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
}
