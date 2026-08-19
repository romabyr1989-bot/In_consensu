package ru.example.inconsensu.audit.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.example.inconsensu.audit.domain.PdnAccessLogEntry;

public interface PdnAccessLogRepository
        extends JpaRepository<PdnAccessLogEntry, Long>, JpaSpecificationExecutor<PdnAccessLogEntry> {}
