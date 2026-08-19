package ru.example.cus.thirdparty.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ru.example.cus.thirdparty.domain.ThirdParty;

/** Третье лицо в ответе API (FR-7.1, UI-11). */
public record ThirdPartyResponse(
        UUID id,
        String name,
        String shortName,
        String inn,
        String ogrn,
        String address,
        String role,
        String roleRu,
        String contractNumber,
        LocalDate contractDate,
        LocalDate contractValidUntil,
        Long contractDaysLeft,
        boolean contractExpired,
        List<String> allowedPdnCategories,
        String contactEmail,
        boolean active) {

    public static ThirdPartyResponse of(ThirdParty thirdParty, LocalDate today) {
        return new ThirdPartyResponse(
                thirdParty.getId(),
                thirdParty.getName(),
                thirdParty.getShortName(),
                thirdParty.getInn(),
                thirdParty.getOgrn(),
                thirdParty.getAddress(),
                thirdParty.getRole().name(),
                thirdParty.getRole().nameRu(),
                thirdParty.getContractNumber(),
                thirdParty.getContractDate(),
                thirdParty.getContractValidUntil(),
                thirdParty.daysUntilContractEnds(today),
                thirdParty.isContractExpired(today),
                List.copyOf(thirdParty.getAllowedPdnCategories()),
                thirdParty.getContactEmail(),
                thirdParty.isActive());
    }
}
