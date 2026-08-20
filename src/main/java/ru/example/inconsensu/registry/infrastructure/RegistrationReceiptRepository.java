package ru.example.inconsensu.registry.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.registry.domain.RegistrationReceipt;

/** Квитанции об обработанных запросах регистрации (FR-4.1). */
public interface RegistrationReceiptRepository extends JpaRepository<RegistrationReceipt, UUID> {

    Optional<RegistrationReceipt> findByIdempotencyKey(String idempotencyKey);
}
