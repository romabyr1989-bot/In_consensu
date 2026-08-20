package ru.example.inconsensu.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.catalog.domain.FormRenderer;
import ru.example.inconsensu.catalog.domain.FormRequisitesValidator;
import ru.example.inconsensu.catalog.domain.FormValidationInput;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.catalog.infrastructure.ConsentFormRepository;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Конструктор форм согласий: черновики, пункты, версии, предпросмотр и проверка реквизитов (§7.1, §7.3). */
@Service
public class ConsentFormService {

    public static final String AGGREGATE_TYPE = "consent_form";

    /** Тестовый субъект для предпросмотра (FR-1.2). Данные вымышленные (§14.6). */
    private static final Map<String, String> PREVIEW_SUBJECT = Map.of(
            "subject.fio", "Травин Иван Сергеевич",
            "subject.phone", "+7 (916) 000-00-41",
            "subject.email", "travin@example.ru");

    public record ItemForm(
            String consentTypeCode,
            String text,
            List<String> purposes,
            List<String> pdnCategories,
            UUID thirdPartyId,
            String validity,
            boolean mandatory) {}

    public record FormDraft(
            String title,
            String body,
            String processingActions,
            String revocationProcedure,
            Set<ConsentSource> sourceChannels,
            List<ItemForm> items) {}

    private final ConsentFormRepository repository;
    private final ConsentTypeService consentTypes;
    private final ThirdPartyService thirdParties;
    private final PdnCategoryService pdnCategories;
    private final OperatorSettingsService settings;
    private final AuditService auditService;

    public ConsentFormService(
            ConsentFormRepository repository,
            ConsentTypeService consentTypes,
            ThirdPartyService thirdParties,
            PdnCategoryService pdnCategories,
            OperatorSettingsService settings,
            AuditService auditService) {
        this.repository = repository;
        this.consentTypes = consentTypes;
        this.thirdParties = thirdParties;
        this.pdnCategories = pdnCategories;
        this.settings = settings;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<ConsentForm> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public ConsentForm get(UUID id) {
        return repository.findWithItemsById(id).orElseThrow(() -> ApiException.notFound("Форма согласия не найдена"));
    }

    /**
     * Текстовый diff двух версий формы (FR-3.2, этап 8).
     *
     * <p>Сравнивается канонический рендер, а не исходное тело: юриста интересует то, что увидит клиент, а
     * не расстановка плейсхолдеров.
     */
    @Transactional(readOnly = true)
    public List<ru.example.inconsensu.catalog.domain.TextDiff.Line> diff(UUID beforeId, UUID afterId) {
        return ru.example.inconsensu.catalog.domain.TextDiff.compare(
                canonicalText(get(beforeId)), canonicalText(get(afterId)));
    }

    /** Фильтры списка форм (FR-3.1, UI-7). Пустое поле означает «без ограничения». */
    public record FormFilter(
            FormStatus status, ConsentSource source, String consentTypeCode, UUID thirdPartyId, String text) {

        public static FormFilter empty() {
            return new FormFilter(null, null, null, null, null);
        }
    }

    /**
     * Список форм с фильтрами (FR-3.1).
     *
     * <p>Все условия, включая источник применения, уходят в запрос: фильтрация уже выбранной страницы
     * давала бы неверное число страниц и прятала подходящие формы, оказавшиеся на других страницах.
     */
    @Transactional(readOnly = true)
    public Page<ConsentForm> list(FormFilter filter, Pageable pageable) {
        if (filter == null) {
            return list(pageable);
        }
        org.springframework.data.jpa.domain.Specification<ConsentForm> specification = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.text() != null && !filter.text().isBlank()) {
                String pattern = "%" + filter.text().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(root.get("code")), pattern),
                        builder.like(builder.lower(root.get("body")), pattern)));
            }
            if (filter.consentTypeCode() != null && !filter.consentTypeCode().isBlank()) {
                var items = root.join("items", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(builder.equal(items.get("consentType").get("code"), filter.consentTypeCode()));
                query.distinct(true);
            }
            if (filter.thirdPartyId() != null) {
                var items = root.join("items", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(builder.equal(items.get("thirdPartyId"), filter.thirdPartyId()));
                query.distinct(true);
            }
            if (filter.source() != null) {
                // Источники применения хранятся столбцом text[]; вхождение проверяется запросом, а не
                // фильтрацией уже выбранной страницы: та прятала бы формы, оказавшиеся дальше по списку,
                // и возвращала бы неверное число страниц.
                //
                // Сравнение с нулём, а не проверка на null: Hibernate оборачивает функцию в
                // coalesce(..., 0), из-за чего «is not null» истинно всегда и фильтр молча исчезает.
                predicates.add(builder.greaterThan(
                        builder.function(
                                "array_position",
                                Integer.class,
                                root.get("sourceChannels"),
                                builder.literal(filter.source().name())),
                        0));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };

        return repository.findAll(specification, pageable);
    }

    @Transactional(readOnly = true)
    public List<ConsentForm> versionsOf(String code) {
        return repository.findByCodeOrderByVersionNumberAsc(code);
    }

    /** Сколько форм опубликовано сейчас — плитка дашборда и статистика каталога (UI-2). */
    @Transactional(readOnly = true)
    public long publishedCount() {
        return repository.countByStatusIs(FormStatus.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public List<ConsentForm> awaitingDecision() {
        return repository.findAwaitingDecision(FormStatus.ON_REVIEW);
    }

    /** FR-2.3: регистрировать согласия можно только по опубликованной версии, действующей на момент подписания. */
    @Transactional(readOnly = true)
    public Optional<ConsentForm> publishedVersionOf(String code) {
        return repository.findFirstByCodeAndStatusOrderByVersionNumberDesc(code, FormStatus.PUBLISHED);
    }

    /**
     * Версия формы, действовавшая на указанный момент (FR-2.3).
     *
     * <p>Нужна импорту исторических согласий: полученное три года назад согласие ссылается на версию,
     * которая давно в архиве, и подставлять вместо неё текущую опубликованную — значит записать клиенту
     * условия, которых он не видел.
     */
    @Transactional(readOnly = true)
    public Optional<ConsentForm> versionEffectiveAt(String code, java.time.Instant moment) {
        return repository.findByCodeOrderByVersionNumberAsc(code).stream()
                .filter(form -> form.getStatus() == FormStatus.PUBLISHED || form.getStatus() == FormStatus.ARCHIVED)
                .filter(form -> form.isEffectiveAt(moment))
                .reduce((first, second) -> second);
    }

    @Transactional
    public ConsentForm createDraft(String code, FormDraft draft) {
        if (repository.existsByCode(code)) {
            throw ApiException.conflict("Форма с таким кодом уже существует: создайте новую версию");
        }
        ConsentForm form = new ConsentForm(UUID.randomUUID(), code, 1, draft.title(), draft.body());
        applyDraft(form, draft);
        ConsentForm saved = repository.save(form);
        auditService.record(AGGREGATE_TYPE, aggregateId(saved), AuditEventType.CREATED, describe(saved));
        return saved;
    }

    @Transactional
    public ConsentForm editDraft(UUID id, FormDraft draft) {
        ConsentForm form = get(id);
        try {
            applyDraft(form, draft);
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
        ConsentForm saved = repository.save(form);
        auditService.record(AGGREGATE_TYPE, aggregateId(saved), AuditEventType.UPDATED, describe(saved));
        return saved;
    }

    /** FR-1.5: правка опубликованной формы — это новая версия, а не изменение старой. */
    @Transactional
    public ConsentForm createNewVersion(UUID id) {
        ConsentForm source = get(id);
        repository.findFirstByCodeOrderByVersionNumberDesc(source.getCode()).ifPresent(latest -> {
            if (latest.getStatus() == FormStatus.DRAFT || latest.getStatus() == FormStatus.ON_REVIEW) {
                throw ApiException.conflict("У формы уже есть незавершённая версия " + latest.getVersionNumber()
                        + ": завершите работу над ней");
            }
        });
        ConsentForm next;
        try {
            next = source.newVersion(UUID.randomUUID());
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
        ConsentForm saved = repository.save(next);
        auditService.record(AGGREGATE_TYPE, aggregateId(saved), AuditEventType.CREATED, describe(saved));
        return saved;
    }

    @Transactional
    public void deleteDraft(UUID id) {
        ConsentForm form = get(id);
        if (form.getStatus() != FormStatus.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT, "Удалить можно только черновик формы");
        }
        auditService.record(AGGREGATE_TYPE, aggregateId(form), AuditEventType.DEACTIVATED, describe(form));
        repository.delete(form);
    }

    /** FR-1.3: тот же валидатор доступен отдельным эндпоинтом для проверки черновика. */
    @Transactional(readOnly = true)
    public FormValidationResult validate(UUID id) {
        return FormRequisitesValidator.validate(validationInput(get(id)));
    }

    /** FR-1.2: предпросмотр с реквизитами оператора и тестовым субъектом. */
    @Transactional(readOnly = true)
    public String preview(UUID id) {
        ConsentForm form = get(id);
        Map<String, String> values = new LinkedHashMap<>(operatorValues());
        values.putAll(PREVIEW_SUBJECT);
        values.putAll(thirdPartyValues(form));
        return FormRenderer.render(form.getBody(), values);
    }

    /**
     * FR-1.6: точный текст версии, каким он был на момент подписания.
     *
     * <p>У опубликованной версии он взят из снимка, сделанного при публикации: пересборка из текущих
     * настроек оператора выдавала бы текст, которого клиент не видел, и ломала бы сверку контрольной суммы
     * в досье согласия.
     */
    @Transactional(readOnly = true)
    public String canonicalText(ConsentForm form) {
        if (form.getRenderedText() != null && !form.getRenderedText().isBlank()) {
            return form.getRenderedText();
        }
        return renderNow(form);
    }

    /** Рендер по текущим справочникам — для черновика, предпросмотра и момента публикации. */
    public String renderNow(ConsentForm form) {
        Map<String, String> values = new LinkedHashMap<>(operatorValues());
        values.putAll(thirdPartyValues(form));
        return FormRenderer.renderCanonical(canonicalForm(form), values);
    }

    /**
     * Версия формы целиком для канонического рендера (FR-1.5, FR-1.6).
     *
     * <p>В сумму входят и обязательные блоки ч. 4 ст. 9 152-ФЗ, и пункты: иначе две версии, различающиеся
     * только ими, оказывались бы неотличимы по контрольной сумме.
     */
    private FormRenderer.CanonicalForm canonicalForm(ConsentForm form) {
        List<FormRenderer.CanonicalForm.Item> items = form.getItems().stream()
                .sorted(java.util.Comparator.comparingInt(ConsentFormItem::getSortOrder))
                .map(item -> new FormRenderer.CanonicalForm.Item(
                        item.getConsentType().getCode(),
                        item.getText(),
                        item.getPurposes(),
                        item.getPdnCategories(),
                        item.getThirdPartyId() == null
                                ? null
                                : thirdParties.get(item.getThirdPartyId()).getName(),
                        item.getValidity(),
                        item.isMandatory()))
                .toList();
        return new FormRenderer.CanonicalForm(
                form.getBody(), form.getProcessingActions(), form.getRevocationProcedure(), items);
    }

    public String checksumOf(ConsentForm form) {
        return FormRenderer.checksum(canonicalText(form));
    }

    FormValidationInput validationInput(ConsentForm form) {
        List<FormValidationInput.Item> items = new ArrayList<>();
        for (ConsentFormItem item : form.getItems()) {
            ThirdParty thirdParty = item.getThirdPartyId() == null ? null : thirdParties.get(item.getThirdPartyId());
            ConsentType type = item.getConsentType();
            items.add(new FormValidationInput.Item(
                    type.getCode(),
                    type.getNameRu(),
                    type.getCategory(),
                    type.isRequiresThirdParty(),
                    item.getText(),
                    item.getPurposes(),
                    item.getPdnCategories(),
                    pdnCategories.anySpecial(item.getPdnCategories()),
                    thirdParty == null ? null : thirdParty.getName(),
                    thirdParty == null ? null : thirdParty.getAddress(),
                    item.isMandatory(),
                    type.isActive()));
        }
        return new FormValidationInput(
                settings.value("operator.name"),
                settings.value("operator.address"),
                form.getBody(),
                form.getProcessingActions(),
                form.getRevocationProcedure(),
                items);
    }

    private Map<String, String> operatorValues() {
        return FormRenderer.operatorValues(settings.value("operator.name"), settings.value("operator.address"));
    }

    /** Реквизиты третьего лица берутся из справочника, а не из текста формы (FR-1.3). */
    private Map<String, String> thirdPartyValues(ConsentForm form) {
        Map<String, String> values = new LinkedHashMap<>();
        form.getItems().stream()
                .map(ConsentFormItem::getThirdPartyId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(id -> {
                    ThirdParty thirdParty = thirdParties.get(id);
                    values.put("third_party.name", thirdParty.getName());
                    values.put("third_party.address", thirdParty.getAddress());
                });
        return values;
    }

    private void applyDraft(ConsentForm form, FormDraft draft) {
        form.edit(
                draft.title(),
                draft.body(),
                draft.processingActions(),
                draft.revocationProcedure(),
                draft.sourceChannels());

        List<ConsentFormItem> items = new ArrayList<>();
        List<ItemForm> forms = draft.items() == null ? List.of() : draft.items();
        for (int index = 0; index < forms.size(); index++) {
            ItemForm itemForm = forms.get(index);
            ConsentType type = consentTypes.getByCode(itemForm.consentTypeCode());
            if (!pdnCategories.allExist(
                    Set.copyOf(itemForm.pdnCategories() == null ? List.of() : itemForm.pdnCategories()))) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Пункт " + (index + 1) + ": указана несуществующая категория ПДн");
            }
            if (itemForm.thirdPartyId() != null) {
                thirdParties.get(itemForm.thirdPartyId());
            }
            try {
                items.add(new ConsentFormItem(
                        UUID.randomUUID(),
                        form,
                        type,
                        index,
                        itemForm.text(),
                        itemForm.purposes(),
                        itemForm.pdnCategories(),
                        itemForm.thirdPartyId(),
                        itemForm.validity(),
                        itemForm.mandatory()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Пункт " + (index + 1) + ": " + e.getMessage());
            }
        }
        form.replaceItems(items);
    }

    static String aggregateId(ConsentForm form) {
        return form.getCode() + ":" + form.getVersionNumber();
    }

    static Map<String, Object> describe(ConsentForm form) {
        return describe(form, null);
    }

    /**
     * Описание формы для журнала (FR-2.2).
     *
     * @param fromStatus статус до перехода; без него в истории видно «во что» перешли, но не «из чего»
     */
    static Map<String, Object> describe(ConsentForm form, ru.example.inconsensu.common.domain.FormStatus fromStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", form.getCode());
        payload.put("version", form.getVersionNumber());
        if (fromStatus != null) {
            payload.put("fromStatus", fromStatus.name());
        }
        payload.put("status", form.getStatus().name());
        payload.put("items", form.getItems().size());
        payload.put("checksum", form.getRenderedChecksum());
        return payload;
    }
}
