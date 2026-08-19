package ru.example.inconsensu.thirdparty.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;
import ru.example.inconsensu.thirdparty.infrastructure.ThirdPartyRepository;

/** Справочник третьих лиц и контроль срока договора (FR-7.1). */
@Service
public class ThirdPartyService {

    public static final String AGGREGATE_TYPE = "third_party";

    public record ThirdPartyForm(
            String name,
            String shortName,
            String ogrn,
            String address,
            ThirdPartyRole role,
            String contractNumber,
            LocalDate contractDate,
            LocalDate contractValidUntil,
            Set<String> allowedPdnCategories,
            String contactEmail) {}

    private final ThirdPartyRepository repository;
    private final PdnCategoryService pdnCategories;
    private final AuditService auditService;
    private final InConsensuProperties properties;
    private final Clock clock;

    public ThirdPartyService(
            ThirdPartyRepository repository,
            PdnCategoryService pdnCategories,
            AuditService auditService,
            InConsensuProperties properties,
            Clock clock) {
        this.repository = repository;
        this.pdnCategories = pdnCategories;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.timezone());
    }

    @Transactional(readOnly = true)
    public Page<ThirdParty> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /** Все третьи лица для разрезов статистики и экспорта каталога (FR-3.3, FR-3.4). */
    @Transactional(readOnly = true)
    public List<ThirdParty> all() {
        return repository.findAll(Sort.by("name"));
    }

    @Transactional(readOnly = true)
    public ThirdParty get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Третье лицо не найдено"));
    }

    @Transactional(readOnly = true)
    public ThirdParty getByInn(String inn) {
        return repository
                .findByInn(inn)
                .orElseThrow(() -> ApiException.notFound("Третье лицо с ИНН " + inn + " не найдено"));
    }

    @Transactional(readOnly = true)
    public List<ThirdParty> contractsEndingWithin(int days) {
        LocalDate from = today();
        return repository.findWithContractEndingBetween(from, from.plusDays(days));
    }

    @Transactional
    public ThirdParty create(String inn, ThirdPartyForm form) {
        if (repository.existsByInn(inn)) {
            throw ApiException.conflict("Третье лицо с таким ИНН уже есть в справочнике");
        }
        validate(form);
        ThirdParty thirdParty = new ThirdParty(UUID.randomUUID(), form.name(), inn, form.address(), form.role());
        applyForm(thirdParty, form);
        ThirdParty saved = repository.save(thirdParty);
        auditService.record(AGGREGATE_TYPE, saved.getId().toString(), AuditEventType.CREATED, describe(saved));
        return saved;
    }

    @Transactional
    public ThirdParty update(UUID id, ThirdPartyForm form) {
        validate(form);
        ThirdParty thirdParty = get(id);
        applyForm(thirdParty, form);
        ThirdParty saved = repository.save(thirdParty);
        auditService.record(AGGREGATE_TYPE, saved.getId().toString(), AuditEventType.UPDATED, describe(saved));
        return saved;
    }

    @Transactional
    public ThirdParty deactivate(UUID id) {
        ThirdParty thirdParty = get(id);
        thirdParty.deactivate();
        ThirdParty saved = repository.save(thirdParty);
        auditService.record(AGGREGATE_TYPE, saved.getId().toString(), AuditEventType.DEACTIVATED, describe(saved));
        return saved;
    }

    private void validate(ThirdPartyForm form) {
        if (form.contractDate() != null
                && form.contractValidUntil() != null
                && form.contractValidUntil().isBefore(form.contractDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Срок договора не может заканчиваться раньше его даты");
        }
        Set<String> requested = form.allowedPdnCategories() == null ? Set.of() : form.allowedPdnCategories();
        if (!pdnCategories.allExist(requested)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Указана несуществующая категория персональных данных");
        }
    }

    private void applyForm(ThirdParty thirdParty, ThirdPartyForm form) {
        thirdParty.update(
                form.name(),
                form.shortName(),
                form.ogrn(),
                form.address(),
                form.role(),
                form.contractNumber(),
                form.contractDate(),
                form.contractValidUntil(),
                form.allowedPdnCategories(),
                form.contactEmail());
    }

    private Map<String, Object> describe(ThirdParty thirdParty) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inn", thirdParty.getInn());
        payload.put("role", thirdParty.getRole().name());
        payload.put("contractNumber", thirdParty.getContractNumber());
        payload.put("contractValidUntil", String.valueOf(thirdParty.getContractValidUntil()));
        payload.put("allowedPdnCategories", List.copyOf(thirdParty.getAllowedPdnCategories()));
        payload.put("active", thirdParty.isActive());
        return payload;
    }
}
