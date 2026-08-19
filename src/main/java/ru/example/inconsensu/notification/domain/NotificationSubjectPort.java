package ru.example.inconsensu.notification.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Как модуль уведомлений узнаёт, о ком письмо (FR-9.2, FR-9.3).
 *
 * <p>Порт объявлен на стороне потребителя: данные субъекта принадлежат модулю registry, а модуль
 * уведомлений о его репозиториях знать не должен — иначе модули замкнулись бы в цикл (§5).
 */
public interface NotificationSubjectPort {

    Optional<SubjectInfo> find(UUID subjectId);

    /**
     * Минимум сведений, разрешённый FR-9.2 к передаче в письмо: ФИО и внешний идентификатор.
     * В webhook уходит только {@code externalId} (FR-9.3).
     */
    record SubjectInfo(UUID id, String externalId, String fullName) {}
}
