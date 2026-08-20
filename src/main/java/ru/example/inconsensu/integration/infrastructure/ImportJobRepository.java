package ru.example.inconsensu.integration.infrastructure;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.integration.domain.ImportJob;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    Page<ImportJob> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** Задачи с ошибками для блока дашборда UI-2: отбор запросом, а не из последних десяти задач. */
    @org.springframework.data.jpa.repository.Query("select j from ImportJob j where j.rejected > 0 or j.status = "
            + "ru.example.inconsensu.integration.domain.ImportJobStatus.FAILED order by j.startedAt desc")
    java.util.List<ImportJob> findFailed(Pageable pageable);
}
