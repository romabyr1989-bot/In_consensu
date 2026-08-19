package ru.example.inconsensu.registry.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.domain.ConsentCountsPort;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;

/** Отдаёт каталогу счётчики согласий (§9 `/catalog/stats`, §5). */
@Component
public class RegistryConsentCountsProvider implements ConsentCountsPort {

    private final ConsentRepository consents;

    public RegistryConsentCountsProvider(ConsentRepository consents) {
        this.consents = consents;
    }

    @Override
    @Transactional(readOnly = true)
    public long activeConsents() {
        return consents.countByStatus(ConsentStatus.ACTIVE) + consents.countByStatus(ConsentStatus.EXPIRING);
    }

    @Override
    @Transactional(readOnly = true)
    public long expiringConsents(Instant from, Instant to) {
        return consents.countExpiringBetween(from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public long revokedConsentsSince(Instant since) {
        return consents.countByRevokedAtAfter(since);
    }

    @Override
    @Transactional(readOnly = true)
    public long activeConsentsOfType(UUID consentTypeId) {
        return consents.countByConsentTypeIdAndStatus(consentTypeId, ConsentStatus.ACTIVE)
                + consents.countByConsentTypeIdAndStatus(consentTypeId, ConsentStatus.EXPIRING);
    }

    @Override
    @Transactional(readOnly = true)
    public long revokedConsentsOfType(UUID consentTypeId) {
        return consents.countByConsentTypeIdAndStatus(consentTypeId, ConsentStatus.REVOKED);
    }
}
