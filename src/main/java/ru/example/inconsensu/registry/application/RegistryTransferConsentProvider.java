package ru.example.inconsensu.registry.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.thirdparty.domain.SubjectTransferPort;
import ru.example.inconsensu.thirdparty.domain.TransferSnapshots;

/** Отдаёт согласия на передачу модулю третьих лиц (§5, порт объявлен потребителем). */
@Component
public class RegistryTransferConsentProvider implements SubjectTransferPort {

    private final ConsentQueryService consents;
    private final ConsentTypeService types;

    public RegistryTransferConsentProvider(ConsentQueryService consents, ConsentTypeService types) {
        this.consents = consents;
        this.types = types;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean baseConsentUsable(UUID subjectId) {
        return consents.effectiveConsentsOf(subjectId).stream()
                .filter(view -> ru.example.inconsensu.channels.domain.ChannelEvaluator.BASE_CONSENT_TYPE_CODE.equals(
                        types.get(view.consent().getConsentTypeId()).getCode()))
                .anyMatch(view -> view.status() == ru.example.inconsensu.common.domain.ConsentStatus.ACTIVE
                        || view.status() == ru.example.inconsensu.common.domain.ConsentStatus.EXPIRING);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferSnapshots.TransferConsent> transferConsentsOf(UUID subjectId) {
        Map<UUID, ConsentType> typeCache = new HashMap<>();
        return consents.currentConsentsOf(subjectId).stream()
                // Передача — это согласие, у которого есть третье лицо: остальные типы к §7.7 отношения не имеют.
                .filter(view -> view.consent().getThirdPartyId() != null)
                .filter(view -> typeCache
                        .computeIfAbsent(view.consent().getConsentTypeId(), types::get)
                        .isRequiresThirdParty())
                .map(view -> new TransferSnapshots.TransferConsent(
                        view.consent().getId(),
                        view.consent().getThirdPartyId(),
                        view.consent().getPdnCategories(),
                        view.status(),
                        view.consent().getValidUntil()))
                .toList();
    }
}
