package ru.example.inconsensu.registry.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.registry.domain.ConsentStatusTexts;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;

/**
 * Чтение согласий: карточка клиента, история и статусы (FR-5.1, FR-5.3, FR-5.4).
 *
 * <p>Статус всегда вычисляется на момент чтения, а не берётся из колонки: колонка материализуется задачей и
 * может отставать на несколько часов, а карточка обязана показывать актуальную картину (FR-5.3).
 */
@Service
public class ConsentQueryService {

    /** Порог «заканчивается через N дней» по умолчанию, если настройка не задана (FR-5.3). */
    public static final int DEFAULT_EXPIRING_DAYS = 30;

    private static final String EXPIRING_DAYS_SETTING = "inconsensu.status.expiring-days";

    /** Согласие вместе с вычисленным на момент чтения статусом. */
    public record ConsentView(Consent consent, ConsentStatus status, String statusText, Long daysLeft) {}

    private final ConsentRepository consents;
    private final ru.example.inconsensu.catalog.application.ConsentTypeService consentTypes;
    private final OperatorSettingsService settings;
    private final PdnAccessLogService pdnAccessLog;
    private final InConsensuProperties properties;
    private final Clock clock;

    public ConsentQueryService(
            ConsentRepository consents,
            ru.example.inconsensu.catalog.application.ConsentTypeService consentTypes,
            OperatorSettingsService settings,
            PdnAccessLogService pdnAccessLog,
            InConsensuProperties properties,
            Clock clock) {
        this.consents = consents;
        this.consentTypes = consentTypes;
        this.settings = settings;
        this.pdnAccessLog = pdnAccessLog;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public int expiringDays() {
        String configured = settings.value(EXPIRING_DAYS_SETTING);
        try {
            return configured == null || configured.isBlank()
                    ? DEFAULT_EXPIRING_DAYS
                    : Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_EXPIRING_DAYS;
        }
    }

    /** Эффективные согласия субъекта для карточки (§8.1): не отозванные и не заменённые. */
    @Transactional(readOnly = true)
    public List<ConsentView> effectiveConsentsOf(UUID subjectId) {
        return view(consents.findEffectiveBySubject(subjectId));
    }

    /**
     * Согласия для карточки клиента (FR-5.1, Приложение A, UI-4).
     *
     * <p>Отозванные и истёкшие показываются: карточка обязана отвечать на вопрос «а что было раньше»,
     * иначе менеджер не увидит, что клиент уже отзывал рекламу. Скрыты только заменённые — они появляются
     * по переключателю «Показать заменённые».
     *
     * <p>Порядок строк задан UI-4: сначала истекающие, затем действующие, затем отозванные и истёкшие.
     */
    @Transactional(readOnly = true)
    public List<ConsentView> cardConsentsOf(UUID subjectId) {
        return currentConsentsOf(subjectId).stream()
                .sorted(java.util.Comparator.comparingInt((ConsentView view) -> switch (view.status()) {
                            case EXPIRING -> 0;
                            case ACTIVE -> 1;
                            case REVOKED -> 2;
                            case EXPIRED -> 3;
                            case SUPERSEDED -> 4;
                        })
                        .thenComparing(view -> view.consent().getGrantedAt(), java.util.Comparator.reverseOrder()))
                .toList();
    }

    /**
     * Заменённые согласия субъекта (UI-4, переключатель «Показать заменённые»).
     *
     * <p>Выборка карточки отсекает их в SQL по {@code supersededById is null}, поэтому фильтр в памяти
     * ничего не добавлял: переключатель на экране был, а заменённые не появлялись.
     */
    @Transactional(readOnly = true)
    public List<ConsentView> supersededOf(UUID subjectId) {
        return view(consents.findBySubjectIdAndSupersededByIdNotNull(subjectId));
    }

    /** Полная история, включая заменённые и отозванные (FR-5.1). */
    @Transactional(readOnly = true)
    public List<ConsentView> historyOf(UUID subjectId) {
        pdnAccessLog.recordSingle("/api/v1/subjects/{id}/history", subjectId);
        return view(consents.findBySubjectIdOrderByGrantedAtDesc(subjectId));
    }

    /**
     * Согласие без записи в журнал доступа к ПДн.
     *
     * <p>Нужна лентам и спискам, которые уже зафиксированы одним обращением: {@link #get(UUID)} пишет
     * запись на каждый вызов, и на строке ленты это раздувало бы журнал (FR-5.2, FR-10.5).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ConsentView> find(UUID consentId) {
        return consents.findById(consentId).map(this::view);
    }

    @Transactional(readOnly = true)
    public ConsentView get(UUID consentId) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> ApiException.notFound("Согласие не найдено"));
        pdnAccessLog.recordSingle("/api/v1/consents/{id}", consent.getSubjectId());
        return view(consent);
    }

    public ConsentView view(Consent consent) {
        return view(consent, expiringDays(), clock.instant());
    }

    /**
     * Тот же расчёт, но с уже известным порогом и моментом времени.
     *
     * <p>Порог «заканчивается через N дней» хранится в настройках оператора, и читать его на каждое согласие
     * значило бы делать запрос в базу на строку: на массовой проверке это тысячи лишних обращений (NFR-1).
     */
    public ConsentView view(Consent consent, int expiringDays, Instant now) {
        ZoneId zone = properties.timezone();
        ConsentStatus status = consent.calculatedStatus(now, expiringDays);
        Long daysLeft = consent.getValidUntil() == null
                ? null
                : ru.example.inconsensu.registry.domain.ConsentStatusCalculator.daysLeft(
                        consent.getValidUntil(), now, zone);
        return new ConsentView(
                consent, status, ConsentStatusTexts.textOf(status, consent.getValidUntil(), now, zone), daysLeft);
    }

    private List<ConsentView> view(List<Consent> found) {
        int expiringDays = expiringDays();
        Instant now = clock.instant();
        return found.stream().map(consent -> view(consent, expiringDays, now)).toList();
    }

    /** Фильтры списка согласий (§9). Пустое поле означает «без ограничения». */
    public record ConsentFilter(
            java.util.UUID subjectId,
            String typeCode,
            ru.example.inconsensu.common.domain.ConsentStatus status,
            java.util.UUID thirdPartyId,
            ru.example.inconsensu.common.domain.ConsentSource source,
            Instant validUntilFrom,
            Instant validUntilTo) {}

    /**
     * Поиск согласий по фильтрам.
     *
     * <p>Фильтр по статусу опирается на материализованную колонку: обходить миллионы строк расчётом при
     * чтении нельзя (NFR-1), поэтому её и догоняет ежедневная задача (FR-5.3).
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Consent> search(
            ConsentFilter filter, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<Consent> specification = (root, query, builder) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (filter.subjectId() != null) {
                predicates.add(builder.equal(root.get("subjectId"), filter.subjectId()));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.thirdPartyId() != null) {
                predicates.add(builder.equal(root.get("thirdPartyId"), filter.thirdPartyId()));
            }
            if (filter.source() != null) {
                predicates.add(builder.equal(root.get("source"), filter.source()));
            }
            if (filter.validUntilFrom() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("validUntil"), filter.validUntilFrom()));
            }
            if (filter.validUntilTo() != null) {
                predicates.add(builder.lessThan(root.get("validUntil"), filter.validUntilTo()));
            }
            if (filter.typeCode() != null) {
                predicates.add(builder.equal(root.get("consentTypeId"), typeIdOf(filter.typeCode())));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return consents.findAll(specification, pageable);
    }

    private java.util.UUID typeIdOf(String typeCode) {
        return consentTypes.getByCode(typeCode).getId();
    }

    /** Текущие согласия субъекта, включая отозванные и истёкшие: нужны расчёту каналов (FR-6.1). */
    @Transactional(readOnly = true)
    public List<ConsentView> currentConsentsOf(UUID subjectId) {
        return view(consents.findCurrentBySubject(subjectId));
    }

    /** То же пакетом; ключ — идентификатор субъекта. Один запрос вместо тысяч (NFR-1). */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, List<ConsentView>> currentConsentsOf(java.util.Collection<UUID> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return java.util.Map.of();
        }
        int expiringDays = expiringDays();
        Instant now = clock.instant();
        return consents.findCurrentBySubjects(subjectIds).stream()
                .map(consent -> view(consent, expiringDays, now))
                .collect(java.util.stream.Collectors.groupingBy(
                        view -> view.consent().getSubjectId()));
    }
}
