package ru.example.inconsensu.catalog.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.catalog.infrastructure.ConsentTypeRepository;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;

/** Справочник типов согласий (FR-1.1). */
@Service
public class ConsentTypeService {

    public static final String AGGREGATE_TYPE = "consent_type";

    public record ConsentTypeForm(
            String nameRu,
            String description,
            ConsentCategory category,
            Set<CommunicationChannel> channels,
            boolean requiresThirdParty,
            String defaultValidity,
            String dependsOnCode,
            boolean businessSignificant,
            int sortOrder) {}

    private final ConsentTypeRepository repository;
    private final AuditService auditService;

    public ConsentTypeService(ConsentTypeRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<ConsentType> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /**
     * Типы, которые зависят от указанного, включая цепочку зависимостей (FR-8.4).
     *
     * <p>Обход транзитивный: если появится тип, зависящий от рекламного, отзыв базового согласия обязан
     * погасить и его — иначе каскад оставит «висящее» разрешение.
     */
    /** Прежняя сигнатура: фильтры по категории и активности (FR-3.1, UI-6). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ConsentType> list(
            ru.example.inconsensu.common.domain.ConsentCategory category,
            Boolean active,
            org.springframework.data.domain.Pageable pageable) {
        return list(category, active, null, null, pageable);
    }

    /**
     * Список типов с фильтрами FR-3.1: категория, активность, признак значимости для бизнеса и текст.
     *
     * <p>Условия уходят в запрос: фильтрация уже выбранной страницы прятала бы типы, оказавшиеся дальше.
     *
     * @param businessSignificant флаг из FR-1.1; фильтр по нему живёт здесь, а не в списке форм: признак
     *     принадлежит типу согласия, и у формы его нет
     * @param text подстрока названия или кода, регистр не важен
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ConsentType> list(
            ru.example.inconsensu.common.domain.ConsentCategory category,
            Boolean active,
            Boolean businessSignificant,
            String text,
            org.springframework.data.domain.Pageable pageable) {
        String needle = text == null || text.isBlank() ? null : text.trim().toLowerCase();
        org.springframework.data.jpa.domain.Specification<ConsentType> specification = (root, query, builder) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            if (active != null) {
                predicates.add(builder.equal(root.get("active"), active));
            }
            if (businessSignificant != null) {
                predicates.add(builder.equal(root.get("businessSignificant"), businessSignificant));
            }
            if (needle != null) {
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("nameRu")), "%" + needle + "%"),
                        builder.like(builder.lower(root.get("code")), "%" + needle + "%")));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return repository.findAll(specification, pageable);
    }

    @Transactional(readOnly = true)
    public Set<UUID> dependentTypeIds(UUID typeId) {
        Set<UUID> collected = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(typeId);
        while (!queue.isEmpty()) {
            for (ConsentType dependent : repository.findByDependsOnId(queue.poll())) {
                if (collected.add(dependent.getId())) {
                    queue.add(dependent.getId());
                }
            }
        }
        return collected;
    }

    /** Типы рекламной категории: нужны требованию «прекратить рекламу» (FR-8.1). */
    @Transactional(readOnly = true)
    public Set<UUID> advertisingTypeIds() {
        return repository.findAll().stream()
                .filter(type -> type.getCategory() == ConsentCategory.ADVERTISING)
                .map(ConsentType::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public List<ConsentType> activeTypes() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    /**
     * Справочник целиком, включая деактивированные типы.
     *
     * <p>FR-1.1 требует, чтобы ранее полученные согласия деактивированного типа продолжали действовать и
     * учитываться, поэтому статистика и выгрузка каталога считают по всем типам, а не только по активным.
     */
    @Transactional(readOnly = true)
    public List<ConsentType> allTypes() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public ConsentType get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Тип согласия не найден"));
    }

    @Transactional(readOnly = true)
    public ConsentType getByCode(String code) {
        return repository.findByCode(code).orElseThrow(() -> ApiException.notFound("Тип согласия не найден: " + code));
    }

    @Transactional
    public ConsentType create(String code, ConsentTypeForm form) {
        if (repository.existsByCode(code)) {
            throw ApiException.conflict("Тип согласия с таким кодом уже существует");
        }
        ConsentType type = new ConsentType(UUID.randomUUID(), code, form.nameRu(), form.category());
        apply(type, form);
        ConsentType saved = repository.save(type);
        auditService.record(AGGREGATE_TYPE, saved.getCode(), AuditEventType.CREATED, describe(saved));
        return saved;
    }

    /** Код в форме отсутствует намеренно: он неизменяем после создания (FR-1.1). */
    @Transactional
    public ConsentType update(String code, ConsentTypeForm form) {
        ConsentType type = getByCode(code);
        apply(type, form);
        ConsentType saved = repository.save(type);
        auditService.record(AGGREGATE_TYPE, saved.getCode(), AuditEventType.UPDATED, describe(saved));
        return saved;
    }

    @Transactional
    public ConsentType deactivate(String code) {
        ConsentType type = getByCode(code);
        type.deactivate();
        ConsentType saved = repository.save(type);
        Map<String, Object> payload = describe(saved);
        payload.put(
                "dependentTypes",
                repository.findByDependsOnId(saved.getId()).stream()
                        .map(ConsentType::getCode)
                        .toList());
        auditService.record(AGGREGATE_TYPE, saved.getCode(), AuditEventType.DEACTIVATED, payload);
        return saved;
    }

    private void apply(ConsentType type, ConsentTypeForm form) {
        ConsentType dependsOn = null;
        if (form.dependsOnCode() != null && !form.dependsOnCode().isBlank()) {
            if (form.dependsOnCode().equals(type.getCode())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Тип согласия не может зависеть от самого себя");
            }
            dependsOn = getByCode(form.dependsOnCode());
        }
        try {
            type.update(
                    form.nameRu(),
                    form.description(),
                    form.category(),
                    form.channels() == null ? Set.of() : form.channels(),
                    form.requiresThirdParty(),
                    form.defaultValidity(),
                    dependsOn,
                    form.businessSignificant(),
                    form.sortOrder());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Map<String, Object> describe(ConsentType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", type.getCode());
        payload.put("category", type.getCategory().name());
        payload.put("channels", type.getChannels().stream().map(Enum::name).toList());
        payload.put("requiresThirdParty", type.isRequiresThirdParty());
        payload.put("defaultValidity", type.getDefaultValidity());
        payload.put(
                "dependsOn",
                type.getDependsOn() == null ? null : type.getDependsOn().getCode());
        payload.put("active", type.isActive());
        return payload;
    }
}
