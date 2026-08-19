package ru.example.cus.channels.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.channels.domain.BulkCheckJob;

public interface BulkCheckJobRepository extends JpaRepository<BulkCheckJob, UUID> {}
