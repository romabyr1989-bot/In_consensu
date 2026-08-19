package ru.example.cus.audit.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.example.cus.audit.domain.PdnAccessLogEntry;

public interface PdnAccessLogRepository
        extends JpaRepository<PdnAccessLogEntry, Long>, JpaSpecificationExecutor<PdnAccessLogEntry> {}
