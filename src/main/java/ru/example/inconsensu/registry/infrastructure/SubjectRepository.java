package ru.example.inconsensu.registry.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.registry.domain.Subject;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    @EntityGraph(attributePaths = "contacts")
    Optional<Subject> findWithContactsById(UUID id);

    @EntityGraph(attributePaths = "contacts")
    Optional<Subject> findWithContactsByExternalId(String externalId);

    Optional<Subject> findByExternalId(String externalId);

    /**
     * Догрузка контактов для страницы результатов поиска (FR-5.2).
     *
     * <p>Контакты — ленивая коллекция, а список отдаётся уже вне транзакции; без этой выборки ответ падал бы
     * на {@code LazyInitializationException}, а fetch join в постраничном запросе тянул бы всю выборку в память.
     */
    @EntityGraph(attributePaths = "contacts")
    java.util.List<Subject> findWithContactsByIdIn(java.util.Collection<UUID> ids);

    /**
     * Поиск по нормализованному контакту (FR-5.2): номер, записанный как «8 (916) …», находится по «+7916…».
     */
    @Query(
            """
            select distinct s from Subject s
            join SubjectContact c on c.subject = s
            where c.type = :type and c.valueNormalized = :value
            order by s.lastName, s.firstName, s.id
            """)
    Page<Subject> searchByContact(
            @Param("type") ru.example.inconsensu.common.domain.ContactType type,
            @Param("value") String value,
            Pageable pageable);

    /** Поиск по HMAC нормализованного контакта: используется при включённом шифровании (NFR-3). */
    @Query(
            """
            select distinct s from Subject s
            join SubjectContact c on c.subject = s
            where c.type = :type and c.searchHmac = :hmac
            order by s.lastName, s.firstName, s.id
            """)
    Page<Subject> searchByContactHmac(
            @Param("type") ru.example.inconsensu.common.domain.ContactType type,
            @Param("hmac") String hmac,
            Pageable pageable);

    /**
     * Префиксный поиск по ФИО (FR-5.2, минимум 3 символа).
     *
     * <p>Выражение совпадает с индексом {@code subject_full_name_prefix_idx} — иначе поиск уйдёт в seq scan и
     * цель NFR-1 по карточке станет недостижимой.
     */
    @Query(
            value =
                    """
                    select * from subject
                    where lower(last_name || ' ' || first_name || ' ' || coalesce(middle_name, '')) like :prefix
                    order by last_name, first_name, id
                    """,
            countQuery =
                    """
                    select count(*) from subject
                    where lower(last_name || ' ' || first_name || ' ' || coalesce(middle_name, '')) like :prefix
                    """,
            nativeQuery = true)
    Page<Subject> searchByFullNamePrefix(@Param("prefix") String prefix, Pageable pageable);

    /**
     * Какие из переданных идентификаторов существуют (FR-6.4).
     *
     * <p>Один запрос вместо проверки существования по каждой записи: на массовой проверке разница — тысячи
     * обращений к базе против одного (NFR-1).
     */
    @Query("select s.id from Subject s where s.id in :ids")
    java.util.List<UUID> findExistingIds(@Param("ids") java.util.Collection<UUID> ids);

    /** Разрешение внешних идентификаторов пакетом — для массовой проверки каналов (FR-6.4). */
    @Query("select s.id, s.externalId from Subject s where s.externalId in :externalIds")
    java.util.List<Object[]> findIdsByExternalIds(@Param("externalIds") java.util.Collection<String> externalIds);
}
