package ru.example.inconsensu.notification.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Договоры с третьими лицами, срок которых заканчивается (FR-7.1, FR-9.1). */
public interface ExpiringContractPort {

    List<ExpiringContract> findContractsExpiringIn(int daysBefore);

    record ExpiringContract(UUID thirdPartyId, String name, String contractNumber, LocalDate contractValidUntil) {}
}
