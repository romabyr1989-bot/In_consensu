package ru.example.inconsensu.integration.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.integration.domain.SelfServiceAuthMode;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;

/**
 * Self-service для личного кабинета и мобильного приложения (FR-8.1).
 *
 * <p>Клиент видит только свои согласия и может их отозвать. Режим аутентификации задаётся настройкой:
 * либо клиент приходит со своим JWT, либо личный кабинет — с сервисным токеном и внешним идентификатором.
 * Второй режим опаснее, поэтому идентификатор принимается только у роли INTEGRATION.
 */
@Service
public class SelfServiceService {

    public static final String CLAIM_SUBJECT_EXTERNAL_ID = "subject_external_id";
    private static final String AUTH_MODE_SETTING = "inconsensu.selfservice.auth-mode";

    private final SubjectService subjects;
    private final ConsentQueryService consents;
    private final RevocationService revocation;
    private final ConsentFormService forms;
    private final OperatorSettingsService settings;
    private final SelfServiceRateLimiter rateLimiter;

    public SelfServiceService(
            SubjectService subjects,
            ConsentQueryService consents,
            RevocationService revocation,
            ConsentFormService forms,
            OperatorSettingsService settings,
            SelfServiceRateLimiter rateLimiter) {
        this.subjects = subjects;
        this.consents = consents;
        this.revocation = revocation;
        this.forms = forms;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public SelfServiceAuthMode authMode() {
        String configured = settings.value(AUTH_MODE_SETTING);
        try {
            return configured == null || configured.isBlank()
                    ? SelfServiceAuthMode.SERVICE_TOKEN
                    : SelfServiceAuthMode.valueOf(configured.trim());
        } catch (IllegalArgumentException e) {
            return SelfServiceAuthMode.SERVICE_TOKEN;
        }
    }

    /**
     * Имеет ли вызывающий право обращаться к самообслуживанию (FR-8.1).
     *
     * <p>В режиме SERVICE_TOKEN это делает личный кабинет сервисным токеном роли INTEGRATION, в режиме
     * SUBJECT_JWT — сам клиент токеном со своим внешним идентификатором. Обычный сотрудник не должен
     * попадать сюда ни в одном из режимов: отзыв от имени клиента — не его операция.
     */
    public boolean callerAllowed() {
        return switch (authMode()) {
            case SUBJECT_JWT -> subjectExternalIdFromToken().isPresent();
            case SERVICE_TOKEN -> ru.example.inconsensu.common.security.CurrentUser.roles().stream()
                    .anyMatch(role -> ru.example.inconsensu.common.domain.RoleCode.INTEGRATION
                                    .name()
                                    .equals(role)
                            || ru.example.inconsensu.common.domain.RoleCode.ADMIN
                                    .name()
                                    .equals(role));
        };
    }

    private java.util.Optional<String> subjectExternalIdFromToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String externalId = jwt.getClaimAsString(CLAIM_SUBJECT_EXTERNAL_ID);
            if (externalId != null && !externalId.isBlank()) {
                return java.util.Optional.of(externalId);
            }
        }
        return java.util.Optional.empty();
    }

    /** Кто именно обращается: определяется режимом, а не желанием вызывающего (FR-8.1). */
    @Transactional(readOnly = true)
    public Subject currentSubject(String externalIdFromRequest) {
        String externalId =
                switch (authMode()) {
                    case SUBJECT_JWT -> externalIdFromToken();
                    case SERVICE_TOKEN -> requireServiceProvidedId(externalIdFromRequest);
                };
        rateLimiter.check(externalId);
        return subjects.findByExternalId(externalId).orElseThrow(() -> ApiException.notFound("Клиент не найден"));
    }

    @Transactional(readOnly = true)
    public List<ConsentQueryService.ConsentView> myConsents(Subject subject) {
        // UI-18: клиент видит и отозванные — из них складывается блок «История ваших отзывов».
        return consents.cardConsentsOf(subject.getId());
    }

    /** Точный текст формы, по которой дано согласие (FR-8.1). */
    @Transactional(readOnly = true)
    public String consentText(Subject subject, UUID consentId) {
        var view = ownConsent(subject, consentId);
        if (view.consent().getFormId() == null) {
            return "Текст формы недоступен: согласие получено до внедрения электронного каталога форм.";
        }
        return forms.canonicalText(forms.get(view.consent().getFormId()));
    }

    /**
     * Ограничение частоты для встраиваемой страницы (FR-8.1).
     *
     * <p>Лимит стоял на входе в API самообслуживания, а страница UI-18 работает по открытой сессии и мимо
     * него: «Отозвать» в цикле ничем не ограничивалось. Ключ — тот же внешний идентификатор клиента, так
     * что предел общий на оба пути.
     */
    public void checkRate(Subject subject) {
        rateLimiter.check(subject.getExternalId());
    }

    @Transactional
    public RevocationService.RevocationResult revoke(Subject subject, UUID consentId, RevocationSource source) {
        ownConsent(subject, consentId);
        return revocation.revoke(
                consentId, "Отзыв клиентом через самообслуживание", source, caseNumber(subject), java.util.Map.of());
    }

    /** FR-8.1: «Отказаться от всей рекламы» — все рекламные типы гасятся одним действием. */
    @Transactional
    public List<RevocationService.RevocationResult> revokeAllAdvertising(Subject subject, RevocationSource source) {
        // Самообслуживание идёт из личного кабинета: скан заявления там не нужен (FR-8.2).
        return revocation.revokeAllAdvertising(
                subject.getId(), "Требование клиента прекратить рекламу", source, caseNumber(subject), Map.of());
    }

    /** Номер обращения возвращается клиенту как подтверждение (FR-8.1, UI-18). */
    public String caseNumber(Subject subject) {
        return "SS-" + subject.getExternalId() + "-"
                + Long.toString(System.nanoTime(), 36).toUpperCase();
    }

    /** Чужое согласие недоступно даже по прямой ссылке. */
    private ConsentQueryService.ConsentView ownConsent(Subject subject, UUID consentId) {
        var view = consents.get(consentId);
        if (!view.consent().getSubjectId().equals(subject.getId())) {
            throw ApiException.notFound("Согласие не найдено");
        }
        return view;
    }

    private String externalIdFromToken() {
        return subjectExternalIdFromToken()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNAUTHORIZED,
                        "В токене нет идентификатора клиента (claim " + CLAIM_SUBJECT_EXTERNAL_ID + ")"));
    }

    private String requireServiceProvidedId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "В режиме SERVICE_TOKEN нужно передать внешний идентификатор клиента");
        }
        return externalId;
    }
}
