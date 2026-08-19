package ru.example.inconsensu.catalog.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.FormStatus;

public interface ConsentFormRepository extends JpaRepository<ConsentForm, UUID>, JpaSpecificationExecutor<ConsentForm> {

    /**
     * Тип графа — LOAD, а не FETCH по умолчанию: в режиме FETCH все не перечисленные связи становятся ленивыми,
     * и тип согласия внутри пункта превращался бы в прокси, который при выключенном open-in-view уже некому
     * инициализировать.
     */
    @EntityGraph(attributePaths = "items", type = EntityGraph.EntityGraphType.LOAD)
    Optional<ConsentForm> findWithItemsById(UUID id);

    List<ConsentForm> findByCodeOrderByVersionNumberAsc(String code);

    Optional<ConsentForm> findByCodeAndVersionNumber(String code, int versionNumber);

    boolean existsByCode(String code);

    Optional<ConsentForm> findFirstByCodeOrderByVersionNumberDesc(String code);

    /** Опубликованная версия формы: их не может быть больше одной одновременно (FR-1.5). */
    Optional<ConsentForm> findFirstByCodeAndStatusOrderByVersionNumberDesc(String code, FormStatus status);

    List<ConsentForm> findByStatus(FormStatus status);

    /** Плитка дашборда «опубликованные формы» (UI-2). */
    long countByStatusIs(FormStatus status);

    /** Сводная статистика каталога для дашборда UI-2 и отчётов (FR-3.4). */
    @Query("select f.status, count(f) from ConsentForm f group by f.status")
    List<Object[]> countByStatus();

    @Query("select f from ConsentForm f where f.status = :status order by f.updatedAt desc")
    List<ConsentForm> findAwaitingDecision(@Param("status") FormStatus status);
}
