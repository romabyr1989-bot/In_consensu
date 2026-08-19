package ru.example.inconsensu.catalog.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.example.inconsensu.catalog.domain.ConsentType;

public interface ConsentTypeRepository extends JpaRepository<ConsentType, UUID>, JpaSpecificationExecutor<ConsentType> {

    Optional<ConsentType> findByCode(String code);

    boolean existsByCode(String code);

    List<ConsentType> findByActiveTrueOrderBySortOrderAsc();

    /** Типы, зависящие от указанного: используется каскадным отзывом FR-8.4 и предупреждением при деактивации. */
    List<ConsentType> findByDependsOnId(UUID dependsOnId);
}
