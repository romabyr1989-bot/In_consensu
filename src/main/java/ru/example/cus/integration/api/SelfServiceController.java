package ru.example.cus.integration.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.common.api.ApiTime;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.integration.application.SelfServiceService;
import ru.example.cus.registry.application.ConsentQueryService;
import ru.example.cus.registry.application.RevocationService;
import ru.example.cus.registry.domain.Subject;

/**
 * §9: самообслуживание клиента из личного кабинета и мобильного приложения (FR-8.1).
 *
 * <p>Ответы намеренно скупы: клиенту показываются его согласия и статусы, но не служебные сведения о том,
 * какими системами и по какому договору эти данные обрабатываются.
 */
@RestController
@RequestMapping("/api/v1/self")
@PreAuthorize("isAuthenticated()")
public class SelfServiceController {

    public record SelfConsentResponse(
            UUID id,
            String typeCode,
            String title,
            String status,
            String statusText,
            OffsetDateTime grantedAt,
            OffsetDateTime validUntil,
            boolean revocable) {}

    public record RevocationReceipt(
            OffsetDateTime revokedAt, String caseNumber, List<UUID> revokedConsentIds, String message) {}

    private final SelfServiceService selfService;
    private final ru.example.cus.integration.application.SelfUiSessionService uiSessions;
    private final ru.example.cus.catalog.application.ConsentTypeService types;
    private final CusProperties properties;

    public SelfServiceController(
            SelfServiceService selfService,
            ru.example.cus.integration.application.SelfUiSessionService uiSessions,
            ru.example.cus.catalog.application.ConsentTypeService types,
            CusProperties properties) {
        this.selfService = selfService;
        this.uiSessions = uiSessions;
        this.types = types;
        this.properties = properties;
    }

    /** @param externalId идентификатор клиента; в режиме SUBJECT_JWT берётся из токена и может быть только своим */
    public record UiSessionRequest(String externalId) {}

    public record UiSessionResponse(String url, OffsetDateTime expiresAt) {}

    /**
     * FR-8.1, UI-18: одноразовая ссылка на страницу самообслуживания.
     *
     * <p>Ссылка живёт пять минут и гасится при первом открытии: личный кабинет получает её в момент, когда
     * клиент нажал «Мои согласия», и сразу открывает во фрейме.
     */
    @PostMapping("/ui-sessions")
    public UiSessionResponse createUiSession(@RequestBody(required = false) UiSessionRequest request) {
        var link = uiSessions.issue(request == null ? null : request.externalId());
        return new UiSessionResponse(link.url(), ApiTime.at(link.expiresAt(), properties.timezone()));
    }

    @GetMapping("/consents")
    public List<SelfConsentResponse> myConsents(
            @RequestParam(name = "externalId", required = false) String externalId) {
        Subject subject = selfService.currentSubject(externalId);
        return selfService.myConsents(subject).stream().map(this::toResponse).toList();
    }

    @GetMapping("/consents/{id}/text")
    public Map<String, String> consentText(
            @PathVariable UUID id, @RequestParam(name = "externalId", required = false) String externalId) {
        Subject subject = selfService.currentSubject(externalId);
        return Map.of("text", selfService.consentText(subject, id));
    }

    @PostMapping("/consents/{id}/revoke")
    public RevocationReceipt revoke(
            @PathVariable UUID id, @RequestParam(name = "externalId", required = false) String externalId) {
        Subject subject = selfService.currentSubject(externalId);
        RevocationService.RevocationResult result = selfService.revoke(subject, id, RevocationSource.PERSONAL_ACCOUNT);
        return receipt(List.of(result), "Согласие отозвано");
    }

    /** FR-8.1: одно действие вместо перебора рекламных согласий по одному. */
    @PostMapping("/consents/revoke-all-advertising")
    public RevocationReceipt revokeAllAdvertising(
            @RequestParam(name = "externalId", required = false) String externalId) {
        Subject subject = selfService.currentSubject(externalId);
        var results = selfService.revokeAllAdvertising(subject, RevocationSource.PERSONAL_ACCOUNT);
        return receipt(results, "Мы перестанем присылать вам рекламу");
    }

    private RevocationReceipt receipt(List<RevocationService.RevocationResult> results, String message) {
        if (results.isEmpty()) {
            return new RevocationReceipt(null, null, List.of(), "Отзывать нечего: действующих согласий нет");
        }
        var first = results.get(0);
        List<UUID> ids = results.stream()
                .flatMap(result -> result.all().stream())
                .map(ru.example.cus.registry.domain.Consent::getId)
                .distinct()
                .toList();
        return new RevocationReceipt(
                ApiTime.at(first.revokedAt(), properties.timezone()), first.caseNumber(), ids, message);
    }

    private SelfConsentResponse toResponse(ConsentQueryService.ConsentView view) {
        var type = types.get(view.consent().getConsentTypeId());
        return new SelfConsentResponse(
                view.consent().getId(),
                type.getCode(),
                type.getNameRu(),
                view.status().name(),
                view.statusText(),
                ApiTime.at(view.consent().getGrantedAt(), properties.timezone()),
                ApiTime.at(view.consent().getValidUntil(), properties.timezone()),
                view.consent().getRevokedAt() == null);
    }
}
