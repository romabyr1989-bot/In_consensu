package ru.example.cus.ui.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.domain.ConsentStatus;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.integration.application.SelfUiSessionService;
import ru.example.cus.integration.domain.SelfUiSession;
import ru.example.cus.registry.application.ConsentQueryService;
import ru.example.cus.registry.application.RevocationService;
import ru.example.cus.registry.application.SubjectService;
import ru.example.cus.registry.domain.Subject;

/**
 * Модель страницы самообслуживания (UI-18).
 *
 * <p>Клиенту показываются только его согласия и понятные последствия отзыва: никакие иные ПДн и служебные
 * сведения на страницу не попадают.
 */
@Service
public class UiSelfServiceViewService {

    /** Источник обращения для отзыва через страницу: клиент действует сам из личного кабинета. */
    private static final RevocationSource SOURCE = RevocationSource.PERSONAL_ACCOUNT;

    private static final String REASON = "Отзыв через личный кабинет";

    /** @param greeting обращение по имени и отчеству, без фамилии — на странице клиента этого достаточно */
    public record SelfPage(String greeting, List<SelfConsent> consents, List<SelfConsent> revoked) {}

    public record SelfConsent(
            UUID id,
            String title,
            String status,
            String statusText,
            String grantedAt,
            String validUntil,
            String consequence,
            boolean revocable) {}

    /** @param message «Согласие отозвано 17.08.2026 14:05, обращение — ОБР-…» */
    public record Receipt(String message, List<String> revokedTitles) {}

    private final SelfUiSessionService sessions;
    private final SubjectService subjects;
    private final ConsentQueryService consents;
    private final RevocationService revocation;
    private final ru.example.cus.catalog.application.ConsentTypeService types;
    private final ru.example.cus.integration.application.SelfServiceService selfService;
    private final UiFormats formats;

    public UiSelfServiceViewService(
            SelfUiSessionService sessions,
            SubjectService subjects,
            ConsentQueryService consents,
            RevocationService revocation,
            ru.example.cus.catalog.application.ConsentTypeService types,
            ru.example.cus.integration.application.SelfServiceService selfService,
            UiFormats formats) {
        this.sessions = sessions;
        this.subjects = subjects;
        this.consents = consents;
        this.revocation = revocation;
        this.types = types;
        this.selfService = selfService;
        this.formats = formats;
    }

    /** @return идентификатор открытой сессии страницы */
    @Transactional
    public UUID open(String token) {
        return sessions.open(token).getId();
    }

    @Transactional(readOnly = true)
    public SelfPage page(UUID sessionId) {
        SelfUiSession session = sessions.activeSession(sessionId);
        Subject subject = subjects.get(session.getSubjectId());
        List<ConsentQueryService.ConsentView> effective = consents.effectiveConsentsOf(subject.getId());

        List<SelfConsent> active = effective.stream()
                .filter(view -> view.status() != ConsentStatus.REVOKED)
                .map(this::toSelfConsent)
                .toList();
        List<SelfConsent> revokedList = effective.stream()
                .filter(view -> view.status() == ConsentStatus.REVOKED)
                .map(this::toSelfConsent)
                .toList();

        return new SelfPage(greeting(subject), active, revokedList);
    }

    @Transactional(readOnly = true)
    public String consentText(UUID sessionId, UUID consentId) {
        SelfUiSession session = sessions.activeSession(sessionId);
        return selfService.consentText(subjects.get(session.getSubjectId()), consentId);
    }

    @Transactional
    public Receipt revoke(UUID sessionId, UUID consentId) {
        SelfUiSession session = sessions.activeSession(sessionId);
        Subject subject = subjects.get(session.getSubjectId());
        RevocationService.RevocationResult result = selfService.revoke(subject, consentId, SOURCE);
        return receipt(List.of(result));
    }

    @Transactional
    public Receipt revokeAllAdvertising(UUID sessionId) {
        SelfUiSession session = sessions.activeSession(sessionId);
        Subject subject = subjects.get(session.getSubjectId());
        return receipt(selfService.revokeAllAdvertising(subject, SOURCE));
    }

    private Receipt receipt(List<RevocationService.RevocationResult> results) {
        if (results.isEmpty()) {
            return new Receipt("Отзывать нечего: действующих рекламных согласий не найдено", List.of());
        }
        RevocationService.RevocationResult first = results.get(0);
        List<String> titles = results.stream()
                .flatMap(result -> result.all().stream())
                .map(consent -> types.get(consent.getConsentTypeId()).getNameRu())
                .distinct()
                .toList();
        return new Receipt(
                "Согласие отозвано %s, обращение — %s"
                        .formatted(formats.dateTime(first.revokedAt()), first.caseNumber()),
                titles);
    }

    private SelfConsent toSelfConsent(ConsentQueryService.ConsentView view) {
        var type = types.get(view.consent().getConsentTypeId());
        // Базовое согласие определяется тем же кодом, что и в правиле §7.6: «нет базового — нельзя ничего».
        boolean base = ru.example.cus.channels.domain.ChannelEvaluator.BASE_CONSENT_TYPE_CODE.equals(type.getCode());
        return new SelfConsent(
                view.consent().getId(),
                type.getNameRu(),
                view.status().name(),
                view.statusText(),
                formats.date(view.consent().getGrantedAt()),
                formats.validUntil(view.consent().getValidUntil()),
                consequence(base, type.getChannels().isEmpty()),
                view.status() == ConsentStatus.ACTIVE || view.status() == ConsentStatus.EXPIRING);
    }

    /** UI-18: последствия отзыва объясняются словами клиента, а не терминами системы. */
    private static String consequence(boolean base, boolean withoutChannels) {
        if (base) {
            return "Мы прекратим обработку ваших данных, кроме случаев, когда обязаны хранить их по закону; "
                    + "часть услуг может стать недоступной.";
        }
        return withoutChannels
                ? "Мы перестанем использовать ваши данные для этой цели."
                : "Мы перестанем обращаться к вам по этому каналу с предложениями.";
    }

    private static String greeting(Subject subject) {
        String middle = subject.getMiddleName() == null ? "" : " " + subject.getMiddleName();
        return "Здравствуйте, " + subject.getFirstName() + middle + "!";
    }
}
