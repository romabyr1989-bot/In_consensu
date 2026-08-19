package ru.example.cus.thirdparty.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.common.api.PageResponse;
import ru.example.cus.common.domain.ThirdPartyRole;
import ru.example.cus.thirdparty.application.ThirdPartyService;

/** §9: справочник третьих лиц. Чтение — всем, изменение — ADMIN, DPO, LAWYER (Приложение E). */
@RestController
@RequestMapping("/api/v1/third-parties")
@PreAuthorize("isAuthenticated()")
public class ThirdPartyController {

    public record ThirdPartyRequest(
            @NotBlank @Size(max = 512) String name,
            @Size(max = 255) String shortName,
            @Size(max = 15) String ogrn,
            @NotBlank String address,
            @NotNull ThirdPartyRole role,
            @Size(max = 128) String contractNumber,
            LocalDate contractDate,
            LocalDate contractValidUntil,
            Set<String> allowedPdnCategories,
            @Email @Size(max = 255) String contactEmail) {

        ThirdPartyService.ThirdPartyForm toForm() {
            return new ThirdPartyService.ThirdPartyForm(
                    name,
                    shortName,
                    ogrn,
                    address,
                    role,
                    contractNumber,
                    contractDate,
                    contractValidUntil,
                    allowedPdnCategories == null ? Set.of() : allowedPdnCategories,
                    contactEmail);
        }
    }

    public record CreateThirdPartyRequest(
            @NotBlank @Pattern(regexp = "\\d{10}|\\d{12}", message = "ИНН должен содержать 10 или 12 цифр") String inn,
            @Valid @NotNull ThirdPartyRequest thirdParty) {}

    private final ThirdPartyService service;

    public ThirdPartyController(ThirdPartyService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ThirdPartyResponse> list(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        LocalDate today = service.today();
        return PageResponse.of(service.list(pageable), thirdParty -> ThirdPartyResponse.of(thirdParty, today));
    }

    @GetMapping("/{id}")
    public ThirdPartyResponse get(@PathVariable UUID id) {
        return ThirdPartyResponse.of(service.get(id), service.today());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ThirdPartyResponse create(@Valid @RequestBody CreateThirdPartyRequest request) {
        return ThirdPartyResponse.of(
                service.create(request.inn(), request.thirdParty().toForm()), service.today());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ThirdPartyResponse update(@PathVariable UUID id, @Valid @RequestBody ThirdPartyRequest request) {
        return ThirdPartyResponse.of(service.update(id, request.toForm()), service.today());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ThirdPartyResponse deactivate(@PathVariable UUID id) {
        return ThirdPartyResponse.of(service.deactivate(id), service.today());
    }
}
