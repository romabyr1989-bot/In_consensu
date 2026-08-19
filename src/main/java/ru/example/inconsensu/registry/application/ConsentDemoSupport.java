package ru.example.inconsensu.registry.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.registry.infrastructure.ConsentRepository;

/**
 * Операции над согласиями, нужные только демонстрационным данным (§11).
 *
 * <p>Живёт в модуле, которому принадлежит агрегат: по §5 соседний модуль не имеет права трогать чужой
 * репозиторий. Профиль {@code demo} гарантирует, что в эксплуатации этих операций нет вовсе.
 */
@Service
@Profile("demo")
public class ConsentDemoSupport {

    private final ConsentRepository consents;

    public ConsentDemoSupport(ConsentRepository consents) {
        this.consents = consents;
    }

    /** Полноценный отзыв с каскадом появится на этапе 5; демо достаточно доменной операции. */
    @Transactional
    public void revoke(UUID consentId, Instant revokedAt, String reason, RevocationSource source) {
        Consent consent = consents.findById(consentId).orElseThrow(() -> ApiException.notFound("Согласие не найдено"));
        consent.revoke(revokedAt, reason, source);
        consents.save(consent);
    }

    /** §11: демо обязано показать согласие, истекающее ровно через 15 дней. */
    @Transactional
    public void setValidUntil(UUID consentId, Instant validUntil) {
        consents.updateValidUntil(consentId, validUntil);
    }
}
