package ru.example.cus.common.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.common.domain.PdnCategory;

public interface PdnCategoryRepository extends JpaRepository<PdnCategory, UUID> {

    List<PdnCategory> findByActiveTrueOrderBySortOrderAsc();

    List<PdnCategory> findByCodeIn(Collection<String> codes);
}
