package ru.example.inconsensu.channels.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Как модуль каналов получает согласия субъекта.
 *
 * <p>Инверсия зависимости: правило §7.6 живёт здесь, а данные принадлежат модулю registry. Если бы каналы
 * обращались к registry напрямую, а карточка клиента к каналам, модули замкнулись бы в цикл, запрещённый §5.
 * Порт объявлен на стороне потребителя, реализация — на стороне владельца данных.
 */
public interface SubjectConsentPort {

    /** Текущие согласия субъекта: без заменённых, но с отозванными и истёкшими (нужны для причин запрета). */
    List<ConsentSnapshot> currentConsentsOf(UUID subjectId);

    /** То же пакетом: массовая проверка не должна выполнять запрос на каждого субъекта (NFR-1). */
    Map<UUID, List<ConsentSnapshot>> currentConsentsOf(Collection<UUID> subjectIds);

    /** Разрешение идентификаторов запроса (UUID или внешних) в идентификаторы субъектов. */
    ResolvedSubjects resolve(Collection<String> identifiers);

    /** @param byIdentifier исходный идентификатор из запроса → идентификатор субъекта */
    record ResolvedSubjects(Map<String, UUID> byIdentifier, List<String> unknown) {}
}
