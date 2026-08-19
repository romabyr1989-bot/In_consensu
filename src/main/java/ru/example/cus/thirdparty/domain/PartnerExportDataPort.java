package ru.example.cus.thirdparty.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Данные субъектов для выгрузки партнёру (FR-7.4).
 *
 * <p>Порт объявлен здесь по той же причине, что и остальные: модуль registry уже зависит от thirdparty, и
 * обратная зависимость замкнула бы модули в цикл (§5).
 */
public interface PartnerExportDataPort {

    /**
     * Субъекты с действующим согласием на передачу указанному третьему лицу.
     *
     * @param allowedCategories категории, которые вообще разрешено выгружать; всё остальное не должно даже
     *     покидать модуль registry — фильтрация на стороне владельца данных, а не получателя (NFR-3)
     */
    List<ExportRow> rowsFor(UUID thirdPartyId, Set<String> allowedCategories, Instant now);

    /** @param values значения только разрешённых категорий: ключ — код категории ПДн */
    record ExportRow(String externalId, Map<String, String> values) {}
}
