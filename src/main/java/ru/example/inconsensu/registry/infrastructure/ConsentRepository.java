package ru.example.inconsensu.registry.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.registry.domain.Consent;

public interface ConsentRepository extends JpaRepository<Consent, UUID>, JpaSpecificationExecutor<Consent> {

    Optional<Consent> findByIdempotencyKey(String idempotencyKey);

    /**
     * Все согласия одного запроса (FR-4.1).
     *
     * <p>Ключ запроса дополняется идентификатором пункта: одна форма порождает несколько согласий, а
     * уникальный индекс должен оставаться на строке.
     */
    List<Consent> findByIdempotencyKeyStartingWith(String prefix);

    List<Consent> findBySubjectIdOrderByGrantedAtDesc(UUID subjectId);

    /**
     * Эффективные согласия субъекта: не отозванные и не заменённые (§8.1).
     *
     * <p>Именно они учитываются при расчёте каналов и передач, поэтому выборка узкая и опирается на индекс
     * (subject_id, consent_type_id, status).
     */
    @Query(
            """
            select c from Consent c
            where c.subjectId = :subjectId and c.revokedAt is null and c.supersededById is null
            order by c.grantedAt desc
            """)
    List<Consent> findEffectiveBySubject(@Param("subjectId") UUID subjectId);

    /**
     * Предыдущее эффективное согласие той же пары «тип + третье лицо» (FR-4.3).
     *
     * <p>Третье лицо сравнивается с учётом null: согласие на обработку и согласие на передачу конкретному
     * партнёру — разные записи, замещать друг друга они не должны.
     */
    @Query(
            """
            select c from Consent c
            where c.subjectId = :subjectId
              and c.consentTypeId = :consentTypeId
              and ((:thirdPartyId is null and c.thirdPartyId is null) or c.thirdPartyId = :thirdPartyId)
              and c.revokedAt is null and c.supersededById is null
              and c.id <> :excludeId
            """)
    List<Consent> findEffectiveForSupersede(
            @Param("subjectId") UUID subjectId,
            @Param("consentTypeId") UUID consentTypeId,
            @Param("thirdPartyId") UUID thirdPartyId,
            @Param("excludeId") UUID excludeId);

    /** Кандидаты на пересчёт статуса ежедневной задачей (FR-5.3). */
    @Query(
            """
            select c from Consent c
            where c.revokedAt is null and c.supersededById is null and c.validUntil is not null
              and c.validUntil <= :horizon
            """)
    List<Consent> findForStatusRefresh(@Param("horizon") Instant horizon);

    /**
     * Действующие согласия, срок которых заканчивается в заданное окно (FR-9.1).
     *
     * <p>Окно, а не «ровно N дней»: календарный день считается в таймзоне оператора вызывающей стороной,
     * иначе согласие, истекающее в 00:30 по Москве, попало бы не в тот день (§8.7).
     */
    @Query(
            """
            select c from Consent c
            where c.supersededById is null and c.revokedAt is null
              and c.validUntil >= :from and c.validUntil < :to
            order by c.validUntil, c.id
            """)
    List<Consent> findExpiringBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select c from Consent c
            where c.supersededById is null and c.revokedAt is null
              and c.consentTypeId = :consentTypeId
              and c.validUntil >= :from and c.validUntil < :to
            order by c.validUntil, c.id
            """)
    List<Consent> findExpiringBetweenByType(
            @Param("from") Instant from, @Param("to") Instant to, @Param("consentTypeId") UUID consentTypeId);

    long countByStatus(ConsentStatus status);

    long countByConsentTypeIdAndStatus(UUID consentTypeId, ConsentStatus status);

    /**
     * Разрез статистики каталога по типам согласий (FR-3.4).
     *
     * <p>Один групповой запрос вместо счётчика на каждый тип: типов в справочнике десятки, а вызывается
     * статистика на каждой отрисовке дашборда.
     */
    @Query(
            """
            select c.consentTypeId as groupId, c.status as status, count(c) as total from Consent c
            group by c.consentTypeId, c.status
            """)
    List<StatusCountRow> countGroupedByType();

    /** Тот же разрез по третьим лицам; согласия без третьего лица в выборку не попадают (FR-3.4). */
    @Query(
            """
            select c.thirdPartyId as groupId, c.status as status, count(c) as total from Consent c
            where c.thirdPartyId is not null
            group by c.thirdPartyId, c.status
            """)
    List<StatusCountRow> countGroupedByThirdParty();

    /** Сколько согласий каждого типа истекает в окне — колонка «истекают за 30 дней» (FR-3.4). */
    @Query(
            """
            select c.consentTypeId as groupId, count(c) as total from Consent c
            where c.supersededById is null and c.revokedAt is null
              and c.validUntil is not null and c.validUntil between :from and :to
            group by c.consentTypeId
            """)
    List<GroupCountRow> countExpiringGroupedByType(@Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            select c.thirdPartyId as groupId, count(c) as total from Consent c
            where c.thirdPartyId is not null and c.supersededById is null and c.revokedAt is null
              and c.validUntil is not null and c.validUntil between :from and :to
            group by c.thirdPartyId
            """)
    List<GroupCountRow> countExpiringGroupedByThirdParty(@Param("from") Instant from, @Param("to") Instant to);

    /** Проекция группового счётчика по статусам (FR-3.4). */
    interface StatusCountRow {
        UUID getGroupId();

        ConsentStatus getStatus();

        long getTotal();
    }

    /** Проекция группового счётчика без разбивки по статусам (FR-3.4). */
    interface GroupCountRow {
        UUID getGroupId();

        long getTotal();
    }

    /** Сколько согласий отозвано за период — плитка дашборда и статистика каталога (UI-2). */
    long countByRevokedAtAfter(Instant since);

    /** Действующие согласия, срок которых заканчивается в ближайшее окно (UI-2). */
    @Query(
            """
            select count(c) from Consent c
            where c.supersededById is null and c.revokedAt is null
              and c.validUntil is not null and c.validUntil between :from and :to
            """)
    long countExpiringBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Прямая правка срока действия — только для демонстрационных данных профиля {@code demo}.
     *
     * <p>Обычный путь считает {@code valid_until} из формы (FR-4.3); демо же обязано показать согласие,
     * истекающее ровно через 15 дней (§11), а подделывать для этого дату выражения согласия было бы хуже.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Consent c set c.validUntil = :validUntil where c.id = :id")
    void updateValidUntil(@Param("id") UUID id, @Param("validUntil") Instant validUntil);

    /**
     * Текущие согласия субъекта: без заменённых, но вместе с отозванными и истёкшими.
     *
     * <p>Расчёт каналов обязан объяснить не только «можно», но и «нельзя, потому что отозвано» (FR-6.1),
     * поэтому выборка шире, чем эффективные согласия.
     */
    @Query("select c from Consent c where c.subjectId = :subjectId and c.supersededById is null")
    List<Consent> findCurrentBySubject(@Param("subjectId") UUID subjectId);

    /** То же для пакета субъектов: массовая проверка каналов не должна вырождаться в 10 000 запросов (NFR-1). */
    @Query("select c from Consent c where c.subjectId in :subjectIds and c.supersededById is null")
    List<Consent> findCurrentBySubjects(@Param("subjectIds") java.util.Collection<UUID> subjectIds);

    /** Действующие согласия на передачу конкретному третьему лицу — основа выгрузки партнёру (FR-7.4). */
    @Query(
            """
            select c from Consent c
            where c.thirdPartyId = :thirdPartyId
              and c.revokedAt is null and c.supersededById is null
              and (c.validUntil is null or c.validUntil > current_timestamp)
            """)
    List<Consent> findUsableByThirdParty(@Param("thirdPartyId") UUID thirdPartyId);
}
