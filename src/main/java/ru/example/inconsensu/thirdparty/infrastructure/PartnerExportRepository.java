package ru.example.inconsensu.thirdparty.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.inconsensu.thirdparty.domain.PartnerExport;

public interface PartnerExportRepository extends JpaRepository<PartnerExport, UUID> {

    List<PartnerExport> findByThirdPartyIdOrderByRequestedAtDesc(UUID thirdPartyId);
}
