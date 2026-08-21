package ru.example.inconsensu.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.security.Authorities;

/** §9: типы согласий. Чтение — всем сотрудникам, изменение — ADMIN, DPO, LAWYER (Приложение E). */
@RestController
@RequestMapping("/api/v1/consent-types")
// Каталог типов согласий закрыт для служебной роли INTEGRATION (Приложение E).
@PreAuthorize(Authorities.EMPLOYEE)
public class ConsentTypeController {

    public record ConsentTypeRequest(
            @NotBlank @Size(max = 255) String nameRu,
            String description,
            @NotNull ConsentCategory category,
            Set<CommunicationChannel> channels,
            boolean requiresThirdParty,
            @Size(max = 32) String defaultValidity,
            @Size(max = 64) String dependsOnCode,
            boolean businessSignificant,
            int sortOrder) {

        ConsentTypeService.ConsentTypeForm toForm() {
            return new ConsentTypeService.ConsentTypeForm(
                    nameRu,
                    description,
                    category,
                    channels == null ? Set.of() : channels,
                    requiresThirdParty,
                    defaultValidity,
                    dependsOnCode,
                    businessSignificant,
                    sortOrder);
        }
    }

    public record CreateConsentTypeRequest(
            @NotBlank @Size(max = 64) @Pattern(
                            regexp = "[A-Z0-9_]+",
                            message = "Код может содержать только заглавные латинские буквы, цифры и подчёркивание")
                    String code,
            @Valid @NotNull ConsentTypeRequest type) {}

    private final ConsentTypeService service;

    public ConsentTypeController(ConsentTypeService service) {
        this.service = service;
    }

    /** FR-3.1, UI-6: список типов с фильтрами по категории, активности, значимости и тексту. */
    @GetMapping
    public PageResponse<ConsentTypeResponse> list(
            @RequestParam(required = false) ru.example.inconsensu.common.domain.ConsentCategory category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean businessSignificant,
            @RequestParam(required = false) String text,
            @PageableDefault(size = 50, sort = "sortOrder", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.of(
                service.list(category, active, businessSignificant, text, pageable), ConsentTypeResponse::of);
    }

    @GetMapping("/{code}")
    public ConsentTypeResponse get(@PathVariable String code) {
        return ConsentTypeResponse.of(service.getByCode(code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ConsentTypeResponse create(@Valid @RequestBody CreateConsentTypeRequest request) {
        return ConsentTypeResponse.of(
                service.create(request.code(), request.type().toForm()));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ConsentTypeResponse update(@PathVariable String code, @Valid @RequestBody ConsentTypeRequest request) {
        return ConsentTypeResponse.of(service.update(code, request.toForm()));
    }

    @PostMapping("/{code}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN','DPO','LAWYER')")
    public ConsentTypeResponse deactivate(@PathVariable String code) {
        return ConsentTypeResponse.of(service.deactivate(code));
    }
}
