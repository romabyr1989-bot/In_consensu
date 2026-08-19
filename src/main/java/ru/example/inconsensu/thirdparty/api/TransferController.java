package ru.example.inconsensu.thirdparty.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.common.api.ApiTime;
import ru.example.inconsensu.common.api.TransferView;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.thirdparty.application.TransferService;
import ru.example.inconsensu.thirdparty.domain.TransferEvaluator;

/** §9: разрешённые передачи данных третьим лицам (FR-7.2, FR-7.3). */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('MANAGER','MARKETING','INTEGRATION','DPO','ADMIN')")
public class TransferController {

    public record TransferCheckRequest(
            @NotNull UUID subjectId, @NotNull UUID thirdPartyId, List<String> pdnCategories) {}

    public record TransferCheckResponse(
            boolean allowed,
            List<String> allowedCategories,
            List<String> deniedCategories,
            OffsetDateTime validUntil,
            String reason) {}

    private final TransferService transfers;
    private final InConsensuProperties properties;

    public TransferController(TransferService transfers, InConsensuProperties properties) {
        this.transfers = transfers;
        this.properties = properties;
    }

    @GetMapping("/subjects/{id}/transfers")
    public List<TransferView> transfersOf(@PathVariable UUID id) {
        return transfers.transfersOf(id).stream().map(this::toView).toList();
    }

    @PostMapping("/transfers/check")
    public TransferCheckResponse check(@Valid @RequestBody TransferCheckRequest request) {
        TransferEvaluator.TransferCheck result =
                transfers.check(request.subjectId(), request.thirdPartyId(), request.pdnCategories());
        return new TransferCheckResponse(
                result.allowed(),
                result.allowedCategories(),
                result.deniedCategories(),
                ApiTime.at(result.validUntil(), properties.timezone()),
                result.reason());
    }

    private TransferView toView(TransferEvaluator.TransferPermission permission) {
        return new TransferView(
                new TransferView.ThirdPartyRef(
                        permission.thirdPartyId(), permission.thirdPartyName(), permission.thirdPartyRole()),
                permission.allowedCategories(),
                ApiTime.at(permission.validUntil(), properties.timezone()),
                permission.daysLeft(),
                permission.basisConsentId(),
                permission.contractExpired());
    }
}
