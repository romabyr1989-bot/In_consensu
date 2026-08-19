package ru.example.cus.audit.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.domain.AuditVerification;
import ru.example.cus.audit.infrastructure.AuditVerificationRepository;

/**
 * Запись результата проверки в отдельной транзакции (FR-10.4).
 *
 * <p>Отдельный бин, потому что {@code @Transactional} действует через прокси: фоновая задача вызывает эти
 * методы из другого потока, где транзакции вызывающего уже нет.
 */
@Component
public class AuditVerificationStore {

    private final AuditVerificationRepository repository;
    private final Clock clock;

    public AuditVerificationStore(AuditVerificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditVerification create(String startedBy) {
        return repository.save(new AuditVerification(UUID.randomUUID(), startedBy, clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID id, AuditIntegrityService.Report report, String problemsJson) {
        repository.findById(id).ifPresent(verification -> {
            verification.complete(
                    clock.instant(),
                    report.integrity().name(),
                    report.aggregatesChecked(),
                    report.eventsChecked(),
                    report.anchorsChecked(),
                    problemsJson);
            repository.save(verification);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID id, String error) {
        repository.findById(id).ifPresent(verification -> {
            verification.fail(clock.instant(), error);
            repository.save(verification);
        });
    }
}
