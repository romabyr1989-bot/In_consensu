package ru.example.inconsensu.ui.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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
    private final ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf;
    private final ru.example.inconsensu.catalog.application.ConsentTypeService consentTypes;

    private final ru.example.inconsensu.ui.application.UiFormats formats;

    public UiApiController(
            UiDashboardService dashboard,
            UiThirdPartyViewService thirdParties,
            UiBrandingService branding,
            UiSubjectViewService subjectView,
            ru.example.inconsensu.registry.application.RevocationService revocation,
            ru.example.inconsensu.registry.application.ConsentEvidenceService evidence,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf,
            ru.example.inconsensu.catalog.application.ConsentTypeService consentTypes,
            ru.example.inconsensu.ui.application.UiFormats formats) {
        this.dashboard = dashboard;
        this.thirdParties = thirdParties;
        this.branding = branding;
        this.subjectView = subjectView;
        this.revocation = revocation;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
        this.cardPdf = cardPdf;
        this.consentTypes = consentTypes;
        this.formats = formats;
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
        return subjectView.search(query, PageRequest.of(0, 50)).getContent().stream()
                .map(this::searchRow)
                .toList();
    }

    private SubjectSearchRow searchRow(UiSubjectViewService.SubjectRow row) {
        return new SubjectSearchRow(
                row.subject().getId(),
                row.subject().getFullName(),
                row.subject().getExternalId(),
                contact(row, ru.example.inconsensu.common.domain.ContactType.PHONE),
                contact(row, ru.example.inconsensu.common.domain.ContactType.EMAIL),
                row.active(),
                row.expiring(),
                row.revoked());
    }

    private static String text(String value) {
        return value == null ? "" : value;
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

    /**
     * UI-2, UI-3: список клиентов по фильтрам, без поискового запроса.
     *
     * <p>Плитка главной открывает именно такой список — «все, у кого согласие заканчивается», — и
     * поискового запроса у сотрудника при этом нет. Отбор идёт GET-ом: в нём нет персональных данных,
     * только коды справочников, и такую ссылку можно положить в закладки (UI-0.8).
     */
    @GetMapping("/subjects")
    public Map<String, Object> listSubjects(
            @RequestParam(required = false) ru.example.inconsensu.common.domain.ConsentStatus status,
            @RequestParam(required = false) UUID consentTypeId,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) ru.example.inconsensu.common.domain.ConsentSource source,
            @RequestParam(required = false) String expiringBefore,
            @RequestParam(defaultValue = "false") boolean revokedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        var filter = new ru.example.inconsensu.registry.application.SubjectService.SubjectFilter(
                status,
                consentTypeId,
                thirdPartyId,
                source,
                expiringBefore == null || expiringBefore.isBlank()
                        ? null
                        : subjectView.startOfNextDay(java.time.LocalDate.parse(expiringBefore)),
                revokedOnly);
        if (filter.isEmpty()) {
            return Map.of("rows", List.of(), "total", 0L, "hint", "Выберите хотя бы один отбор или введите запрос.");
        }
        var found = subjectView.search(null, filter, PageRequest.of(Math.max(page, 0), size, order(sort, direction)));
        return Map.of(
                "rows", found.getContent().stream().map(this::searchRow).toList(),
                "total", found.getTotalElements());
    }

    /** UI-0.8: сортировка разрешена только по колонкам списка — иначе имя поля уходит прямо в запрос. */
    private static org.springframework.data.domain.Sort order(String sort, String direction) {
        String property =
                switch (sort == null ? "" : sort) {
                    case "fullName" -> "lastName";
                    case "externalId" -> "externalId";
                    case "birthDate" -> "birthDate";
                    default -> null;
                };
        if (property == null) {
            // Без явной сортировки работает нативный запрос под индексом префикса ФИО: порядок задаёт он.
            return org.springframework.data.domain.Sort.unsorted();
        }
        return org.springframework.data.domain.Sort.by(
                "desc".equalsIgnoreCase(direction)
                        ? org.springframework.data.domain.Sort.Direction.DESC
                        : org.springframework.data.domain.Sort.Direction.ASC,
                property);
    }

    /** Справочники панели отбора (UI-3): статусы, источники, типы согласий и партнёры. */
    @GetMapping("/subjects/filters")
    public Map<String, Object> subjectFilters() {
        return Map.of(
                "statuses",
                        java.util.Arrays.stream(ru.example.inconsensu.common.domain.ConsentStatus.values())
                                .map(status -> Map.of("code", status.name(), "nameRu", status.nameRu()))
                                .toList(),
                "sources",
                        java.util.Arrays.stream(ru.example.inconsensu.common.domain.ConsentSource.values())
                                .map(source -> Map.of("code", source.name(), "nameRu", source.nameRu()))
                                .toList(),
                "consentTypes",
                        consentTypes.activeTypes().stream()
                                .map(type -> Map.of("code", type.getId().toString(), "nameRu", type.getNameRu()))
                                .toList(),
                "thirdParties",
                        thirdParties.rows().stream()
                                .map(party -> Map.of("code", party.id().toString(), "nameRu", party.name()))
                                .toList());
    }

    /**
     * UI-0.1: заведение и правка клиента (§9 `POST /subjects` — upsert по внешнему идентификатору).
     *
     * <p>Ключ — внешний идентификатор: повторная отправка того же значения правит запись, а не создаёт
     * вторую (FR-4.4).
     */
    public record SubjectRequest(
            String externalId,
            String lastName,
            String firstName,
            String middleName,
            String birthDate,
            String phone,
            String email) {}

    @org.springframework.web.bind.annotation.PostMapping("/subjects")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public Map<String, String> saveSubject(
            @org.springframework.web.bind.annotation.RequestBody SubjectRequest request) {
        List<ru.example.inconsensu.registry.application.SubjectService.ContactForm> contacts =
                new java.util.ArrayList<>();
        if (request.phone() != null && !request.phone().isBlank()) {
            contacts.add(new ru.example.inconsensu.registry.application.SubjectService.ContactForm(
                    ru.example.inconsensu.common.domain.ContactType.PHONE,
                    request.phone().trim(),
                    true));
        }
        if (request.email() != null && !request.email().isBlank()) {
            contacts.add(new ru.example.inconsensu.registry.application.SubjectService.ContactForm(
                    ru.example.inconsensu.common.domain.ContactType.EMAIL,
                    request.email().trim(),
                    true));
        }
        var saved = subjectView.saveSubject(new ru.example.inconsensu.registry.application.SubjectService.SubjectForm(
                request.externalId().trim(),
                request.lastName().trim(),
                request.firstName().trim(),
                request.middleName() == null || request.middleName().isBlank()
                        ? null
                        : request.middleName().trim(),
                request.birthDate() == null || request.birthDate().isBlank()
                        ? null
                        : java.time.LocalDate.parse(request.birthDate()),
                contacts));
        return Map.of("id", saved.getId().toString(), "message", "Клиент сохранён");
    }

    /**
     * UI-4: карточка согласий одним файлом.
     *
     * <p>В имени файла только идентификатор: ФИО не попадает ни в адрес, ни в имя файла (UI-0.10).
     */
    @GetMapping(value = "/subjects/{id}/card.pdf", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> cardPdf(
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"consent-card-" + id + ".pdf\"")
                .body(cardPdf.render(id));
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
                        : formats.date(card.subject().getBirthDate()),
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
                                text(tile.validUntil()),
                                // У открытого канала причины нет: пустая строка, а не null — иначе экран
                                // печатает «null» там, где не должно быть ничего.
                                text(tile.reasonRu())))
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
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public List<UiSubjectViewService.RevocableConsent> revocable(
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        return subjectView.revocableConsents(id);
    }

    /** UI-5: что погаснет вместе с этим согласием — показывается до подтверждения отзыва. */
    @GetMapping("/consents/{id}/cascade")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
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
            List<EvidenceField> evidence,
            List<UiSubjectViewService.HistoryEntry> events) {}

    /** Поле доказательства: код нужен для сверки, подпись — сотруднику (UI-0.4). */
    public record EvidenceField(String code, String nameRu, String value) {}

    /**
     * Русские подписи полей доказательств и даты в человеческом виде.
     *
     * <p>Экран показывал технические имена (`otpHash`, `userAgent`) и метку времени машинного формата:
     * сотрудник должен читать доказательство, а не разбирать его.
     */
    private List<EvidenceField> evidenceFields(Map<String, Object> evidence) {
        return evidence.entrySet().stream()
                .map(entry -> new EvidenceField(
                        entry.getKey(),
                        EVIDENCE_NAMES.getOrDefault(entry.getKey(), entry.getKey()),
                        evidenceValue(entry.getValue())))
                .sorted(java.util.Comparator.comparing(EvidenceField::nameRu))
                .toList();
    }

    private static final Map<String, String> EVIDENCE_NAMES = Map.ofEntries(
            Map.entry("phone", "Телефон, на который пришёл код"),
            Map.entry("otpHash", "Отпечаток кода подтверждения"),
            Map.entry("otpVerifiedAt", "Код подтверждён"),
            Map.entry("ip", "Адрес, с которого пришёл запрос"),
            Map.entry("userAgent", "Браузер клиента"),
            Map.entry("documentRef", "Ссылка на скан документа"),
            Map.entry("signedAt", "Подписано"),
            Map.entry("signatureId", "Идентификатор подписи"),
            Map.entry("certificateSubject", "Владелец сертификата"),
            Map.entry("operatorLogin", "Сотрудник, оформивший согласие"),
            Map.entry("channel", "Канал обращения"),
            Map.entry("email", "Почта, на которую пришло письмо"));

    /** Метка времени показывается по-русски; остальное — как пришло. */
    private String evidenceValue(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        try {
            return formats.dateTime(java.time.Instant.parse(text));
        } catch (java.time.format.DateTimeParseException notATimestamp) {
            return text;
        }
    }

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
                evidenceFields(evidence.maskedEvidence(dossier.consent(), objectMapper)),
                subjectView.historyFeed(summary.subjectId(), null, null, null).entries().stream()
                        .filter(entry -> id.equals(entry.consentId()))
                        .toList());
    }
}
