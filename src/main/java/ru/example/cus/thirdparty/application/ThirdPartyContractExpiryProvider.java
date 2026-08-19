package ru.example.cus.thirdparty.application;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.notification.domain.ExpiringContractPort;

/** Отдаёт модулю уведомлений договоры, срок которых заканчивается (FR-7.1, FR-9.1). */
@Component
public class ThirdPartyContractExpiryProvider implements ExpiringContractPort {

    private final ThirdPartyService thirdParties;

    public ThirdPartyContractExpiryProvider(ThirdPartyService thirdParties) {
        this.thirdParties = thirdParties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiringContract> findContractsExpiringIn(int daysBefore) {
        return thirdParties.contractsEndingWithin(daysBefore).stream()
                .map(party -> new ExpiringContract(
                        party.getId(), party.getName(), party.getContractNumber(), party.getContractValidUntil()))
                .toList();
    }
}
