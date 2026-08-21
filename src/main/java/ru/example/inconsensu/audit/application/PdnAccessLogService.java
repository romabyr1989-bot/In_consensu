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

    /**
     * Адрес, по которому сотрудник обратился на самом деле (FR-10.5, UI-15).
     *
     * <p>Сервисы называют свой эндпоинт строкой, и открытие карточки из интерфейса писалось в журнал как
     * `/api/v1/subjects/{id}` — аудитор не отличал работу сотрудника от обращения интеграции. Здесь берётся
     * шаблон текущего запроса: он называет и экран, и API, и не содержит идентификаторов.
     */
    private String currentEndpoint(String declared) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) {
            return declared;
        }
        Object pattern = servlet.getRequest()
                .getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? declared : pattern.toString();
    }

    private void record(String endpoint, UUID subjectId, int subjectsCount) {
        repository.save(new PdnAccessLogEntry(
                CurrentUser.id().orElse(null),
                currentEndpoint(endpoint),
                subjectId,
                subjectsCount,
                RequestIdFilter.currentRequestId(),
                clock.instant()));
    }
}
