package ru.example.inconsensu.notification.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Договоры с третьими лицами, срок которых заканчивается (FR-7.1, FR-9.1). */
public interface ExpiringContractPort {

    List<ExpiringContract> findContractsExpiringIn(int daysBefore, UUID thirdPartyId);

    /** Договоры, срок которых уже истёк: FR-7.1 требует уведомить DPO о самом факте. */
    List<ExpiringContract> findContractsAlreadyExpired(UUID thirdPartyId);

    record ExpiringContract(UUID thirdPartyId, String name, String contractNumber, LocalDate contractValidUntil) {}
}
