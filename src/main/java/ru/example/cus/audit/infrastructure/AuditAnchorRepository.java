package ru.example.cus.audit.infrastructure;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.audit.domain.AuditAnchor;

public interface AuditAnchorRepository extends JpaRepository<AuditAnchor, LocalDate> {

    List<AuditAnchor> findAllByOrderByDayAsc();
}
