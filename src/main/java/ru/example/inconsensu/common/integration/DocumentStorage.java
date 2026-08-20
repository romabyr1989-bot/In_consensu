package ru.example.inconsensu.common.integration;

import java.util.Optional;

/**
 * Хранилище сканов и подписанных документов (§3).
 *
 * <p>Само хранилище вне объёма: система держит только ссылку и метаданные, а файл живёт в СЭД или
 * S3-совместимом хранилище заказчика (открытый вопрос 3). Интерфейс нужен, чтобы `documentRef` из
 * доказательств (FR-4.2, FR-4.6, FR-8.2) можно было проверить и показать, не завязываясь на конкретное
 * хранилище.
 */
public interface DocumentStorage {

    /** Метаданные документа: содержимое наружу не отдаётся, чтобы скан не утёк вместе с карточкой. */
    record DocumentInfo(String reference, String name, long sizeBytes, String contentType) {}

    /** Существует ли документ по ссылке; используется при проверке доказательств. */
    boolean exists(String reference);

    Optional<DocumentInfo> describe(String reference);
}
