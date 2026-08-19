package ru.example.cus.integration.infrastructure;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.integration.domain.ImportJob;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    Page<ImportJob> findAllByOrderByStartedAtDesc(Pageable pageable);
}
