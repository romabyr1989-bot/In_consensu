package ru.example.inconsensu.channels.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.channels.domain.BulkCheckJob;

public interface BulkCheckJobRepository extends JpaRepository<BulkCheckJob, UUID> {}
