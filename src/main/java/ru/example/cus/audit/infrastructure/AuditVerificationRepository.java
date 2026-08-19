package ru.example.cus.audit.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.audit.domain.AuditVerification;

public interface AuditVerificationRepository extends JpaRepository<AuditVerification, UUID> {

    List<AuditVerification> findAllByOrderByStartedAtDesc(Pageable pageable);
}
