package ru.example.inconsensu.catalog.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.catalog.domain.FormApproval;

public interface FormApprovalRepository extends JpaRepository<FormApproval, UUID> {

    List<FormApproval> findByFormIdOrderByDecidedAtAsc(UUID formId);
}
