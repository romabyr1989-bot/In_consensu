package ru.example.inconsensu.integration.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.inconsensu.integration.domain.SelfUiSession;

public interface SelfUiSessionRepository extends JpaRepository<SelfUiSession, UUID> {

    Optional<SelfUiSession> findByTokenHash(String tokenHash);

    /** Просроченные ссылки не нужны никому: чистка выполняется вместе с выдачей новой (UI-18). */
    @Modifying
    @Query("delete from SelfUiSession s where s.linkExpiresAt < :before and s.usedAt is null")
    void deleteExpired(@Param("before") Instant before);
}
