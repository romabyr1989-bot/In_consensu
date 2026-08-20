package ru.example.inconsensu.thirdparty.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.notification.domain.ExpiringContractPort;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Отдаёт модулю уведомлений договоры, срок которых заканчивается (FR-7.1, FR-9.1). */
@Component
public class ThirdPartyContractExpiryProvider implements ExpiringContractPort {

    private final ThirdPartyService thirdParties;

    public ThirdPartyContractExpiryProvider(ThirdPartyService thirdParties) {
        this.thirdParties = thirdParties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiringContract> findContractsExpiringIn(int daysBefore, UUID thirdPartyId) {
        return map(thirdParties.contractsEndingWithin(daysBefore), thirdPartyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiringContract> findContractsAlreadyExpired(UUID thirdPartyId) {
        return map(thirdParties.contractsAlreadyExpired(), thirdPartyId);
    }

    /** Правило уведомления может быть сужено до одного партнёра (FR-9.1). */
    private static List<ExpiringContract> map(List<ThirdParty> parties, UUID thirdPartyId) {
        return parties.stream()
                .filter(party -> thirdPartyId == null || thirdPartyId.equals(party.getId()))
                .map(party -> new ExpiringContract(
                        party.getId(), party.getName(), party.getContractNumber(), party.getContractValidUntil()))
                .toList();
    }
}
