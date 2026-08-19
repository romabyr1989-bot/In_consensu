package ru.example.cus.thirdparty.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.cus.thirdparty.domain.PartnerExport;

public interface PartnerExportRepository extends JpaRepository<PartnerExport, UUID> {

    List<PartnerExport> findByThirdPartyIdOrderByRequestedAtDesc(UUID thirdPartyId);
}
