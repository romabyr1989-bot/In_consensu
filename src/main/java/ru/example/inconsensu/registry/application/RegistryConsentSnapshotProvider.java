package ru.example.inconsensu.registry.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.channels.domain.ConsentSnapshot;
import ru.example.inconsensu.channels.domain.SubjectConsentPort;

/**
 * Отдаёт согласия субъекта модулю каналов (§5).
 *
 * <p>Реализация порта живёт здесь, у владельца данных: так модуль каналов не знает ни про репозитории
 * registry, ни про справочник типов, и цикла между модулями не возникает.
 */
@Component
public class RegistryConsentSnapshotProvider implements SubjectConsentPort {

    private final ConsentQueryService consents;
    private final ConsentTypeService types;
    private final SubjectService subjects;

    public RegistryConsentSnapshotProvider(
            ConsentQueryService consents, ConsentTypeService types, SubjectService subjects) {
        this.consents = consents;
        this.types = types;
        this.subjects = subjects;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentSnapshot> currentConsentsOf(UUID subjectId) {
        return toSnapshots(consents.currentConsentsOf(subjectId), new HashMap<>());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<ConsentSnapshot>> currentConsentsOf(Collection<UUID> subjectIds) {
        Map<UUID, ConsentType> typeCache = new HashMap<>();
        Map<UUID, List<ConsentSnapshot>> result = new HashMap<>();
        consents.currentConsentsOf(subjectIds)
                .forEach((subjectId, views) -> result.put(subjectId, toSnapshots(views, typeCache)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedSubjects resolve(Collection<String> identifiers) {
        SubjectService.ResolvedSubjects resolved = subjects.resolve(identifiers);
        return new ResolvedSubjects(resolved.byIdentifier(), resolved.unknown());
    }

    List<ConsentSnapshot> toSnapshots(List<ConsentQueryService.ConsentView> views, Map<UUID, ConsentType> typeCache) {
        return views.stream()
                .map(view -> {
                    ConsentType type = typeCache.computeIfAbsent(view.consent().getConsentTypeId(), types::get);
                    return new ConsentSnapshot(
                            view.consent().getId(),
                            type.getCode(),
                            type.getChannels(),
                            view.status(),
                            view.consent().getGrantedAt(),
                            view.consent().getValidUntil());
                })
                .toList();
    }
}
