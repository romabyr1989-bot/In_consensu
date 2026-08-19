package ru.example.cus.registry.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.catalog.application.ConsentTypeService;
import ru.example.cus.notification.domain.ExpiringConsentPort;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.registry.infrastructure.ConsentRepository;
import ru.example.cus.registry.infrastructure.SubjectRepository;
import ru.example.cus.thirdparty.application.ThirdPartyService;

/**
 * Подбирает согласия для ежедневной задачи уведомлений (FR-9.1, §5).
 *
 * <p>«Ровно N дней» разворачивается в календарные сутки таймзоны оператора: согласие, истекающее в 00:30
 * по Москве, обязано попасть в тот же день, что и истекающее в 23:30 (§8.7).
 */
@Component
public class RegistryExpiringConsentProvider implements ExpiringConsentPort {

    private final ConsentRepository consents;
    private final SubjectRepository subjects;
    private final ConsentTypeService types;
    private final ThirdPartyService thirdParties;
    private final ZoneId zone;
    private final Clock clock;

    public RegistryExpiringConsentProvider(
            ConsentRepository consents,
            SubjectRepository subjects,
            ConsentTypeService types,
            ThirdPartyService thirdParties,
            Clock clock) {
        this.consents = consents;
        this.subjects = subjects;
        this.types = types;
        this.thirdParties = thirdParties;
        this.zone = clock.getZone();
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiringConsent> findExpiringIn(int daysBefore, UUID consentTypeId) {
        LocalDate target = LocalDate.ofInstant(clock.instant(), zone).plusDays(daysBefore);
        Instant from = target.atStartOfDay(zone).toInstant();
        Instant to = target.plusDays(1).atStartOfDay(zone).toInstant();
        return enrich(load(from, to, consentTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiringConsent> findExpiredBetween(Instant from, Instant to, UUID consentTypeId) {
        return enrich(load(from, to, consentTypeId));
    }

    private List<Consent> load(Instant from, Instant to, UUID consentTypeId) {
        return consentTypeId == null
                ? consents.findExpiringBetween(from, to)
                : consents.findExpiringBetweenByType(from, to, consentTypeId);
    }

    private List<ExpiringConsent> enrich(List<Consent> found) {
        Map<UUID, Subject> subjectCache = new HashMap<>();
        Map<UUID, String> typeNames = new HashMap<>();
        Map<UUID, String> partnerNames = new HashMap<>();
        return found.stream()
                .map(consent -> {
                    Subject subject = subjectCache.computeIfAbsent(
                            consent.getSubjectId(), id -> subjects.findById(id).orElse(null));
                    String typeName = typeNames.computeIfAbsent(
                            consent.getConsentTypeId(), id -> types.get(id).getNameRu());
                    String partner = consent.getThirdPartyId() == null
                            ? null
                            : partnerNames.computeIfAbsent(
                                    consent.getThirdPartyId(),
                                    id -> thirdParties.get(id).getName());
                    return new ExpiringConsent(
                            consent.getId(),
                            consent.getSubjectId(),
                            Optional.ofNullable(subject)
                                    .map(Subject::getExternalId)
                                    .orElse(null),
                            Optional.ofNullable(subject)
                                    .map(Subject::getFullName)
                                    .orElse(null),
                            consent.getConsentTypeId(),
                            typeName,
                            partner,
                            consent.getValidUntil());
                })
                .toList();
    }
}
