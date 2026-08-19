package ru.example.cus.thirdparty.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.cus.thirdparty.domain.ThirdParty;

public interface ThirdPartyRepository extends JpaRepository<ThirdParty, UUID> {

    Optional<ThirdParty> findByInn(String inn);

    boolean existsByInn(String inn);

    List<ThirdParty> findByActiveTrueOrderByNameAsc();

    /** Договоры, истекающие в ближайшие N дней: триггер THIRD_PARTY_CONTRACT_EXPIRING (FR-7.1). */
    @Query("select t from ThirdParty t where t.active = true and t.contractValidUntil between :from and :to")
    List<ThirdParty> findWithContractEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
