package ru.example.inconsensu.audit.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.domain.PdnAccessLogEntry;
import ru.example.inconsensu.audit.infrastructure.PdnAccessLogRepository;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.common.web.RequestIdFilter;

/**
 * Records every look at personal data (FR-5.2, FR-10.5).
 *
 * <p>Runs in its own transaction: the fact that somebody looked at a client card is worth keeping even if the
 * surrounding read-only transaction is rolled back afterwards.
 */
@Service
public class PdnAccessLogService {

    private final PdnAccessLogRepository repository;
    private final Clock clock;

    public PdnAccessLogService(PdnAccessLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSingle(String endpoint, UUID subjectId) {
        record(endpoint, subjectId, 1);
    }

    /** One aggregated row per bulk call, as FR-6.4 requires. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBulk(String endpoint, int subjectsCount) {
        record(endpoint, null, subjectsCount);
    }

    private void record(String endpoint, UUID subjectId, int subjectsCount) {
        repository.save(new PdnAccessLogEntry(
                CurrentUser.id().orElse(null),
                endpoint,
                subjectId,
                subjectsCount,
                RequestIdFilter.currentRequestId(),
                clock.instant()));
    }
}
