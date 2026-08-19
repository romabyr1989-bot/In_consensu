package ru.example.cus.thirdparty.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.common.application.PdnCategoryService;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.ThirdPartyRole;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.thirdparty.domain.ThirdParty;
import ru.example.cus.thirdparty.infrastructure.ThirdPartyRepository;

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
    private final CusProperties properties;
    private final Clock clock;

    public ThirdPartyService(
            ThirdPartyRepository repository,
            PdnCategoryService pdnCategories,
            AuditService auditService,
            CusProperties properties,
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
