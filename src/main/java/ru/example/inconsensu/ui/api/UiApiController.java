package ru.example.inconsensu.ui.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.audit.application.AuditIntegrityService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.ui.application.UiBrandingService;
import ru.example.inconsensu.ui.application.UiDashboardService;
import ru.example.inconsensu.ui.application.UiSubjectViewService;
import ru.example.inconsensu.ui.application.UiThirdPartyViewService;

/**
 * JSON для экранов рабочего места (§16).
 *
 * <p>Одностраничное приложение получает данные отсюда, а не из машинной цепочки §12: та отвечает внешним
 * системам по JWT, а сотрудник работает в браузере по серверной сессии с CSRF (UI-0.3). Отдавать браузеру
 * токен ради тех же данных значило бы вынести его в JavaScript и потерять защиту HttpOnly-куки.
 *
 * <p>Формы ответов — те же, что готовят экраны Thymeleaf: используются существующие сервисы представлений,
 * поэтому логика подсчётов, масок и русских подписей не раздваивается.
 */
@RestController
@RequestMapping("/ui/api")
@PreAuthorize("isAuthenticated()")
public class UiApiController {

    /** @param roles коды ролей без префикса ROLE_: по ним интерфейс решает, какие пункты меню показать */
    public record CurrentUser(String login, List<String> roles, String operatorName, String color, String logoUrl) {}

    private final UiDashboardService dashboard;
    private final UiThirdPartyViewService thirdParties;
    private final UiBrandingService branding;
    private final UiSubjectViewService subjectView;
    private final ru.example.inconsensu.registry.application.RevocationService revocation;
    private final ru.example.inconsensu.registry.application.ConsentEvidenceService evidence;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public UiApiController(
            UiDashboardService dashboard,
            UiThirdPartyViewService thirdParties,
            UiBrandingService branding,
            UiSubjectViewService subjectView,
            ru.example.inconsensu.registry.application.RevocationService revocation,
            ru.example.inconsensu.registry.application.ConsentEvidenceService evidence,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.dashboard = dashboard;
        this.thirdParties = thirdParties;
        this.branding = branding;
        this.subjectView = subjectView;
        this.revocation = revocation;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    /** Кто вошёл и как выглядит оператор: с этого приложение начинает работу (UI-0.5, UI-0.12). */
    @GetMapping("/me")
    public CurrentUser me(Authentication authentication) {
        UiBrandingService.Branding brand = branding.branding();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .toList();
        return new CurrentUser(authentication.getName(), roles, brand.operatorName(), brand.color(), brand.logoUrl());
    }

    /** UI-2: плитки-счётчики и блоки главной. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        var stats = dashboard.stats();
        return Map.of(
                "activeConsents", stats.activeConsents(),
                "expiringConsents", stats.expiringConsents(),
                "revokedConsents", stats.revokedConsents(),
                "awaitingApproval", stats.awaitingApproval(),
                "expiringContracts", stats.expiringContracts(),
                "publishedForms", stats.publishedForms(),
                "recentNotifications",
                        dashboard.recentNotifications().stream()
                                .map(notification -> Map.of(
                                        "recipient", notification.getRecipient(),
                                        "subject", notification.getSubjectLine(),
                                        "status", notification.getStatus().name()))
                                .toList(),
                "failedDeliveries", dashboard.failedDeliveries().size(),
                "failedImports", dashboard.failedImports().size());
    }

    /** Строка результата поиска: контакты уже замаскированы по роли (UI-0.10). */
    public record SubjectSearchRow(
            UUID id,
            String fullName,
            String externalId,
            String phone,
            String email,
            long active,
            long expiring,
            long revoked) {}

    /**
     * UI-3: поиск клиента.
     *
     * <p>Запрос принимается POST-ом: телефон, почта и ФИО не должны попадать в адрес и в журналы
     * веб-сервера (UI-0.10). Тип запроса определяет сервер — по первому символу и составу строки.
     */
    @org.springframework.web.bind.annotation.PostMapping("/subjects/search")
    public List<SubjectSearchRow> searchSubjects(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        return subjectView.search(query, org.springframework.data.domain.PageRequest.of(0, 50)).getContent().stream()
                .map(row -> new SubjectSearchRow(
                        row.subject().getId(),
                        row.subject().getFullName(),
                        row.subject().getExternalId(),
                        contact(row, ru.example.inconsensu.common.domain.ContactType.PHONE),
                        contact(row, ru.example.inconsensu.common.domain.ContactType.EMAIL),
                        row.active(),
                        row.expiring(),
                        row.revoked()))
                .toList();
    }

    /** Значение контакта нужного типа — уже в том виде, в каком его вправе видеть эта роль. */
    private static String contact(
            UiSubjectViewService.SubjectRow row, ru.example.inconsensu.common.domain.ContactType type) {
        return row.contacts().stream()
                .filter(contact -> contact.type() == type)
                .map(UiSubjectViewService.ContactView::value)
                .findFirst()
                .orElse("");
    }

    /** Значение справочника: код для сервера, русское название для человека (NFR-8). */
    public record DictionaryItem(String code, String nameRu) {}

    /**
     * Справочники для выпадающих списков.
     *
     * <p>Русские названия отдаёт сервер, а не файл со строками во фронте: иначе одно и то же значение
     * пришлось бы поддерживать в двух местах и они бы разошлись.
     */
    @GetMapping("/dictionaries")
    public Map<String, List<DictionaryItem>> dictionaries() {
        return Map.of(
                "revocationSources",
                        java.util.Arrays.stream(ru.example.inconsensu.common.domain.RevocationSource.values())
                                .map(source -> new DictionaryItem(source.name(), source.nameRu()))
                                .toList(),
                "auditEventTypes",
                        java.util.Arrays.stream(AuditEventType.values())
                                .map(type -> new DictionaryItem(type.name(), type.nameRu()))
                                .toList());
    }

    /** Согласие в карточке и в истории: даты и подписи уже подготовлены сервисом представления. */
    public record ConsentCard(
            UUID id,
            String typeName,
            String status,
            String statusText,
            Long daysLeft,
            String grantedAt,
            String validUntil,
            String source,
            String thirdPartyName,
            String categories,
            boolean revocable,
            boolean contractExpired) {}

    /** @param masked контакт показан в сокращённом виде и может быть раскрыт отдельным действием */
    public record ContactCard(String type, String typeRu, String value, boolean masked) {}

    /** @param reason почему канал закрыт; пусто, если открыт */
    public record ChannelCard(String channel, String nameRu, boolean allowed, String validUntil, String reason) {}

    public record TransferCard(
            String thirdPartyName,
            String role,
            String categories,
            String validUntil,
            String daysLeft,
            UUID basisConsentId,
            boolean contractExpired) {}

    public record SubjectCard(
            UUID id,
            String fullName,
            String externalId,
            String birthDate,
            String summary,
            List<ContactCard> contacts,
            List<ChannelCard> channels,
            List<ConsentCard> consents,
            List<TransferCard> transfers,
            boolean mayReveal,
            boolean mayRevoke) {}

    /** UI-4: карточка клиента целиком — одним запросом, чтобы экран не собирался из пяти обращений. */
    @GetMapping("/subjects/{id}")
    public SubjectCard subjectCard(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean superseded) {
        UiSubjectViewService.CardView card = subjectView.card(id, superseded);
        var roles = ru.example.inconsensu.common.security.CurrentUser.roles();
        return new SubjectCard(
                card.subject().getId(),
                card.subject().getFullName(),
                card.subject().getExternalId(),
                card.subject().getBirthDate() == null
                        ? ""
                        : card.subject().getBirthDate().toString(),
                card.summaryRu(),
                card.contacts().stream()
                        .map(contact -> new ContactCard(
                                contact.type().name(), contact.typeRu(), contact.value(), contact.masked()))
                        .toList(),
                card.channels().stream()
                        .map(tile -> new ChannelCard(
                                tile.channel().name(),
                                tile.nameRu(),
                                tile.allowed(),
                                tile.validUntil(),
                                tile.reasonRu()))
                        .toList(),
                card.consents().stream().map(UiApiController::consentCard).toList(),
                card.transfers().stream()
                        .map(transfer -> new TransferCard(
                                transfer.thirdPartyName(),
                                transfer.thirdPartyRole(),
                                transfer.categoriesRu(),
                                transfer.validUntil(),
                                transfer.daysLeft(),
                                transfer.basisConsentId(),
                                transfer.contractExpired()))
                        .toList(),
                ru.example.inconsensu.registry.domain.ContactAccessPolicy.seesFullContacts(roles),
                roles.stream()
                        .anyMatch(role -> List.of("MANAGER", "DPO", "ADMIN").contains(role)));
    }

    private static ConsentCard consentCard(UiSubjectViewService.ConsentRow row) {
        return new ConsentCard(
                row.view().consent().getId(),
                row.typeNameRu(),
                row.view().status().name(),
                row.view().statusText(),
                row.view().daysLeft(),
                row.grantedAt(),
                row.validUntil(),
                row.source(),
                row.thirdPartyName(),
                row.categoriesRu(),
                row.revocable(),
                row.thirdPartyContractExpired());
    }

    /** @param truncated событий больше, чем показано: остальные видны после сужения периода */
    public record HistoryFeed(List<UiSubjectViewService.HistoryEntry> entries, long total, boolean truncated) {}

    /** UI-4: лента событий клиента с фильтром по типу и периоду. */
    @GetMapping("/subjects/{id}/history")
    public HistoryFeed history(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        AuditEventType type = eventType == null || eventType.isBlank() ? null : AuditEventType.valueOf(eventType);
        var feed = subjectView.historyFeed(id, type, day(from, true), day(to, false));
        return new HistoryFeed(feed.entries(), feed.total(), feed.truncated());
    }

    /** Границы периода: «с» — начало дня, «по» — начало следующего, чтобы день входил в выборку целиком. */
    private java.time.Instant day(String value, boolean start) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.time.LocalDate date = java.time.LocalDate.parse(value);
        return start ? subjectView.startOfDay(date) : subjectView.startOfNextDay(date);
    }

    /** UI-4: проверка целостности цепочки событий клиента. */
    @org.springframework.web.bind.annotation.PostMapping("/subjects/{id}/history/verify")
    public Map<String, Object> verifyHistory(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        var report = subjectView.verifySubjectHistory(id);
        boolean intact = report.integrity() == AuditIntegrityService.Integrity.OK;
        String message = intact
                ? "Целостность подтверждена: проверено событий — " + report.eventsChecked() + "."
                : "Цепочка нарушена. Первое расхождение: "
                        + (report.problems().isEmpty()
                                ? "—"
                                : report.problems().get(0).description());
        return Map.of("intact", intact, "checked", report.eventsChecked(), "message", message);
    }

    /**
     * UI-0.10: раскрытие контакта.
     *
     * <p>POST, а не GET: действие меняет состояние — оно попадает в журнал доступа к ПДн, и повторять его
     * переходом по адресу из истории браузера нельзя.
     */
    @org.springframework.web.bind.annotation.PostMapping("/subjects/{id}/reveal")
    public ContactCard reveal(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        var type = ru.example.inconsensu.common.domain.ContactType.valueOf(body.get("type"));
        var contact = subjectView.revealContact(id, type);
        return new ContactCard(contact.type().name(), contact.typeRu(), contact.value(), contact.masked());
    }

    /** UI-4: чем можно наполнить диалог отзыва — список действующих согласий клиента. */
    @GetMapping("/subjects/{id}/revocable")
    public List<UiSubjectViewService.RevocableConsent> revocable(
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        return subjectView.revocableConsents(id);
    }

    /** UI-5: что погаснет вместе с этим согласием — показывается до подтверждения отзыва. */
    @GetMapping("/consents/{id}/cascade")
    public List<ConsentCard> cascade(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        return subjectView.previewCascade(id).stream()
                .map(UiApiController::consentCard)
                .toList();
    }

    /**
     * UI-5: отзыв согласия.
     *
     * <p>Права проверяются здесь так же, как на экране Thymeleaf: одностраничное приложение прячет кнопку,
     * но прятать — не значит запрещать.
     */
    @org.springframework.web.bind.annotation.PostMapping("/consents/{id}/revoke")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public Map<String, String> revoke(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String documentRef = body.get("documentRef");
        Map<String, Object> evidence =
                documentRef == null || documentRef.isBlank() ? Map.of() : Map.of("documentRef", documentRef);
        var source = ru.example.inconsensu.common.domain.RevocationSource.valueOf(body.get("revocationSource"));
        String reason = body.getOrDefault("reason", "");
        String caseNumber = body.getOrDefault("caseNumber", "");
        UUID subjectId = subjectView.consent(id).consent().getSubjectId();
        var results = Boolean.parseBoolean(body.get("allAdvertising"))
                ? revocation.revokeAllAdvertising(subjectId, reason, source, caseNumber, evidence)
                : List.of(revocation.revoke(id, reason, source, caseNumber, evidence));
        return Map.of("message", subjectView.revocationMessage(results));
    }

    /**
     * Досье согласия (UI-4a).
     *
     * @param checksumMatches контрольная сумма текста формы совпала с сохранённой: доказательство целое
     * @param evidence поля доказательств уже без чувствительных значений — телефон, код и адрес маскированы
     */
    public record ConsentDossier(
            UUID id,
            UUID subjectId,
            String subjectName,
            String consentTypeRu,
            String statusRu,
            String grantedAt,
            String validUntil,
            String source,
            String signatureTypeRu,
            String revokedAt,
            String revocationSourceRu,
            String revocationReason,
            String formTitle,
            String formVersion,
            String formText,
            String storedChecksum,
            boolean checksumMatches,
            boolean integrityOk,
            String integrityMessage,
            Map<String, Object> evidence,
            List<UiSubjectViewService.HistoryEntry> events) {}

    /** UI-4a: доказательство согласия — сведения, текст формы, контрольная сумма и лента событий. */
    @GetMapping("/consents/{id}")
    public ConsentDossier dossier(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        var dossier = evidence.of(id);
        var summary = subjectView.dossierSummary(dossier);
        boolean integrityOk = dossier.integrity() == AuditIntegrityService.Integrity.OK;
        return new ConsentDossier(
                id,
                summary.subjectId(),
                summary.subjectName(),
                summary.consentTypeRu(),
                summary.statusRu(),
                summary.grantedAt(),
                summary.validUntil(),
                summary.source(),
                summary.signatureTypeRu(),
                summary.revokedAt(),
                summary.revocationSourceRu(),
                summary.revocationReason(),
                dossier.form() == null ? "" : dossier.form().getTitle(),
                dossier.form() == null ? "" : String.valueOf(dossier.form().getVersionNumber()),
                dossier.formText(),
                dossier.storedChecksum(),
                dossier.checksumMatches(),
                integrityOk,
                integrityOk
                        ? "Цепочка событий не нарушена."
                        : "Цепочка нарушена. Первое расхождение: "
                                + (dossier.integrityProblems().isEmpty()
                                        ? "—"
                                        : dossier.integrityProblems().get(0).description()),
                evidence.maskedEvidence(dossier.consent(), objectMapper),
                subjectView.historyFeed(summary.subjectId(), null, null, null).entries().stream()
                        .filter(entry -> id.equals(entry.consentId()))
                        .toList());
    }

    /** UI-11: справочник третьих лиц. */
    @GetMapping("/third-parties")
    public List<UiThirdPartyViewService.PartyRow> thirdParties(
            @RequestParam(required = false) String contract,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return thirdParties.rows("EXPIRING".equals(contract), sort, "desc".equalsIgnoreCase(direction));
    }

    /** UI-11: выгрузки партнёру — вкладка карточки третьего лица. */
    @GetMapping("/third-parties/{id}/exports")
    public List<UiThirdPartyViewService.ExportRow> exports(
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        return thirdParties.exports(id);
    }
}
