package ru.example.inconsensu.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.domain.AuditVerification;
import ru.example.inconsensu.audit.infrastructure.AuditVerificationRepository;
import ru.example.inconsensu.common.application.AfterCommitExecutor;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.security.CurrentUser;

/**
 * Асинхронная проверка целостности журнала с историей запусков (FR-10.4, UI-15, ADR-0018).
 *
 * <p>Ответ отдаётся сразу с идентификатором запуска: на объёмах NFR-1 полный пересчёт цепочек не
 * укладывается в таймаут HTTP, а аудитору нужен не мгновенный ответ, а доказуемый результат.
 */
@Service
public class AuditVerificationService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditVerificationService.class);
    private static final int HISTORY_LIMIT = 20;

    private final AuditVerificationRepository repository;
    private final AuditIntegrityService integrityService;
    private final AuditVerificationStore store;
    private final AfterCommitExecutor afterCommit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditVerificationService(
            AuditVerificationRepository repository,
            AuditIntegrityService integrityService,
            AuditVerificationStore store,
            AfterCommitExecutor afterCommit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.integrityService = integrityService;
        this.store = store;
        this.afterCommit = afterCommit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Ставит проверку в очередь и возвращает её идентификатор (FR-10.4). */
    @Transactional
    public AuditVerification start() {
        AuditVerification verification =
                repository.save(new AuditVerification(UUID.randomUUID(), CurrentUser.login(), clock.instant()));
        UUID id = verification.getId();
        // Фоновая проверка стартует после коммита: иначе она не увидит только что созданную запись.
        afterCommit.execute(() -> run(id));
        return verification;
    }

    @Transactional(readOnly = true)
    public AuditVerification get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Проверка не найдена"));
    }

    @Transactional(readOnly = true)
    public List<AuditVerification> history() {
        return repository.findAllByOrderByStartedAtDesc(PageRequest.of(0, HISTORY_LIMIT));
    }

    /** Синхронный прогон для тестов и для демо-сценария: тот же расчёт, что и в фоне. */
    public AuditVerification runNow() {
        AuditVerification verification = store.create(CurrentUser.login());
        run(verification.getId());
        return get(verification.getId());
    }

    private void run(UUID id) {
        try {
            AuditIntegrityService.Report report = integrityService.verifyAll();
            store.complete(id, report, toJson(report.problems()));
        } catch (RuntimeException e) {
            LOG.error("Проверка целостности {} завершилась ошибкой", id, e);
            store.fail(id, e.getClass().getSimpleName());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
