package ru.example.cus.registry.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.notification.domain.NotificationSubjectPort;
import ru.example.cus.registry.infrastructure.SubjectRepository;

/** Отдаёт модулю уведомлений минимум сведений о субъекте (FR-9.2, §5). */
@Component
public class RegistryNotificationSubjectProvider implements NotificationSubjectPort {

    private final SubjectRepository subjects;

    public RegistryNotificationSubjectProvider(SubjectRepository subjects) {
        this.subjects = subjects;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubjectInfo> find(UUID subjectId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        return subjects.findById(subjectId)
                .map(subject -> new SubjectInfo(subject.getId(), subject.getExternalId(), subject.getFullName()));
    }
}
