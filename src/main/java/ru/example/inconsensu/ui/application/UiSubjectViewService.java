package ru.example.inconsensu.ui.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditIntegrityService;
import ru.example.inconsensu.audit.application.AuditQueryService;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.channels.domain.ChannelDecision;
import ru.example.inconsensu.channels.domain.ChannelSummaryComposer;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.registry.application.ConsentEvidenceService;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.ContactAccessPolicy;
import ru.example.inconsensu.registry.domain.ContactMasker;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.registry.domain.SubjectContact;
import ru.example.inconsensu.thirdparty.domain.TransferEvaluator;

/**
 * Модель экранов клиента (UI-3, UI-4, UI-5).
 *
 * <p>Собирает то, что видит сотрудник, из тех же сервисов, что и API: маскирование, статусы и решения по
 * каналам не должны отличаться между экраном и эндпоинтом.
 */
@Service
public class UiSubjectViewService {

    /** @param channels плитки в фиксированном порядке макета UI-4 */
    public record CardView(
            Subject subject,
            List<ContactView> contacts,
            List<ChannelTile> channels,
            String summaryRu,
            List<ConsentRow> consents,
            List<TransferRow> transfers) {}

    public record ContactView(ContactType type, String typeRu, String value, boolean masked) {}

    /** @param reasonRu причина запрета человеческим языком: «согласие отозвано», «нет базового согласия» */
    public record ChannelTile(
            CommunicationChannel channel, String nameRu, boolean allowed, String validUntil, String reasonRu) {}

    /** @param thirdPartyContractExpired FR-7.1: договор с партнёром закончился, передача больше не законна */
    /**
     * Строка вкладки «Передачи третьим лицам» (UI-4).
     *
     * @param basisConsentId согласие-основание: §16 требует ссылку на него прямо во вкладке
     */
    public record TransferRow(
            String thirdPartyName,
            String thirdPartyRole,
            String categoriesRu,
            String validUntil,
            String daysLeft,
            UUID basisConsentId,
            boolean contractExpired) {}

    /** Блок «Сведения о согласии» в досье (UI-4a). */
    public record DossierSummary(
            String consentTypeRu,
            String subjectName,
            UUID subjectId,
            String statusRu,
            String grantedAt,
            String validUntil,
            String source,
            String signatureTypeRu,
            String revokedAt,
            String revocationSourceRu,
            String revocationReason) {}

    /** Согласие, которое можно отозвать: выбор в диалоге, открытом из шапки карточки (UI-4). */
    public record RevocableConsent(UUID id, String title) {}

    public record ConsentRow(
            ConsentQueryService.ConsentView view,
            String typeNameRu,
            String thirdPartyName,
            String categoriesRu,
            String grantedAt,
            String validUntil,
            String source,
            boolean revocable,
            boolean thirdPartyContractExpired) {}

    public record SubjectRow(
            Subject subject,
            List<ContactView> contacts,
            long active,
            long expiring,
            long revoked,
            Map<CommunicationChannel, Boolean> channels) {}

    private final SubjectService subjects;
    private final SubjectCardService cards;
    private final ConsentQueryService consents;
    private final RevocationService revocation;
    private final ru.example.inconsensu.catalog.application.ConsentTypeService types;
    private final ru.example.inconsensu.thirdparty.application.ThirdPartyService thirdParties;
    private final AuditQueryService audit;
    private final AuditIntegrityService integrity;
    private final PdnAccessLogService pdnAccessLog;
    private final UiFormats formats;
    private final ru.example.inconsensu.common.application.PdnCategoryService pdnCategories;
    private final java.time.Clock clock;

    public UiSubjectViewService(
            SubjectService subjects,
            SubjectCardService cards,
            ConsentQueryService consents,
            RevocationService revocation,
            ru.example.inconsensu.catalog.application.ConsentTypeService types,
            ru.example.inconsensu.thirdparty.application.ThirdPartyService thirdParties,
            AuditQueryService audit,
            AuditIntegrityService integrity,
            PdnAccessLogService pdnAccessLog,
            UiFormats formats,
            ru.example.inconsensu.common.application.PdnCategoryService pdnCategories,
            java.time.Clock clock) {
        this.subjects = subjects;
        this.cards = cards;
        this.consents = consents;
        this.revocation = revocation;
        this.types = types;
        this.thirdParties = thirdParties;
        this.audit = audit;
        this.integrity = integrity;
        this.pdnAccessLog = pdnAccessLog;
        this.formats = formats;
        this.pdnCategories = pdnCategories;
        this.clock = clock;
    }

    /**
     * Поиск с панелью расширенных фильтров UI-3.
     *
     * <p>Горизонт «истекающих» берётся из настройки оператора — тот же, что у статусов согласий, иначе
     * фильтр «заканчивается» и бейдж в таблице расходились бы.
     */
    @Transactional(readOnly = true)
    public Page<SubjectRow> search(String query, SubjectService.SubjectFilter filter, Pageable pageable) {
        if (filter == null || filter.isEmpty()) {
            return search(query, pageable);
        }
        java.time.Instant now = clock.instant();
        java.time.Instant horizon = now.plus(consents.expiringDays(), java.time.temporal.ChronoUnit.DAYS);
        boolean fullContacts = ContactAccessPolicy.seesFullContacts(CurrentUser.roles());
        return subjects.search(query, filter, now, horizon, pageable).map(subject -> {
            List<ConsentQueryService.ConsentView> effective = consents.cardConsentsOf(subject.getId());
            return new SubjectRow(
                    subject,
                    contactsOf(subject, fullContacts),
                    count(effective, ConsentStatus.ACTIVE),
                    count(effective, ConsentStatus.EXPIRING),
                    count(effective, ConsentStatus.REVOKED),
                    channelFlags(subject));
        });
    }

    @Transactional(readOnly = true)
    public Page<SubjectRow> search(String query, Pageable pageable) {
        boolean fullContacts = ContactAccessPolicy.seesFullContacts(CurrentUser.roles());
        return subjects.search(query, pageable).map(subject -> {
            List<ConsentQueryService.ConsentView> effective = consents.cardConsentsOf(subject.getId());
            return new SubjectRow(
                    subject,
                    contactsOf(subject, fullContacts),
                    count(effective, ConsentStatus.ACTIVE),
                    count(effective, ConsentStatus.EXPIRING),
                    count(effective, ConsentStatus.REVOKED),
                    channelFlags(subject));
        });
    }

    @Transactional(readOnly = true)
    public CardView card(UUID subjectId) {
        return card(subjectId, false);
    }

    /**
     * Карточка клиента (UI-4).
     *
     * @param showSuperseded добавить к списку заменённые согласия: по умолчанию они скрыты, иначе таблица
     *     у давнего клиента разрастается историей, а нужен текущий срез
     */
    @Transactional(readOnly = true)
    public CardView card(UUID subjectId, boolean showSuperseded) {
        SubjectCardService.SubjectCard card = cards.cardOf(subjectId);
        // UI-0.10: в карточке контакт всегда маскирован, а раскрытие — отдельное действие с записью в
        // журнал доступа к ПДн. Роль решает не «видно сразу», а «можно ли нажать «Показать»».
        return new CardView(
                card.subject(),
                contactsOf(card.subject(), false),
                tiles(card.channels()),
                card.summaryRu(),
                // Заменённые в выборку карточки не попадают (SQL отсекает их по supersededById), поэтому
                // переключатель UI-4 дозапрашивает их отдельно, а не фильтрует уже отфильтрованное.
                java.util.stream.Stream.concat(
                                card.consents().stream(),
                                showSuperseded
                                        ? consents.supersededOf(subjectId).stream()
                                        : java.util.stream.Stream.of())
                        .map(this::row)
                        .toList(),
                transfers(card.transfers()));
    }

    @Transactional(readOnly = true)
    public List<ConsentRow> history(UUID subjectId) {
        return consents.historyOf(subjectId).stream().map(this::row).toList();
    }

    /**
     * Событие ленты истории клиента (UI-4).
     *
     * @param description человекочитаемое описание: «Получено согласие „Реклама по email“, источник — …»
     * @param actorRu кто действовал: сотрудник, клиент, система или внешняя система
     * @param consentId ссылка на согласие, если событие относится к нему
     */
    public record HistoryEntry(
            String occurredAt, String eventTypeRu, String description, String actorRu, UUID consentId) {}

    /**
     * Лента событий по субъекту (UI-4).
     *
     * <p>Раньше вкладка показывала список согласий — то есть состояние, а не историю. UI-4 требует именно
     * ленту: кто, когда и что сделал, со ссылкой на согласие и проверкой целостности цепочки.
     */
    @Transactional(readOnly = true)
    public List<HistoryEntry> historyFeed(UUID subjectId, AuditEventType eventType, Instant from, Instant to) {
        var filter = new AuditQueryService.EventFilter(null, null, eventType, null, subjectId, from, to);
        return audit.events(filter, org.springframework.data.domain.PageRequest.of(0, 200)).getContent().stream()
                .map(this::historyEntry)
                .toList();
    }

    private HistoryEntry historyEntry(ru.example.inconsensu.audit.domain.AuditEvent event) {
        // Тип агрегата, а не перехват исключения: неудачный поиск внутри транзакции помечает её
        // rollback-only, и «поймали и пошли дальше» оборачивается падением всего запроса на коммите.
        UUID consentId = ConsentRegistrationService.AGGREGATE_TYPE.equals(event.getAggregateType())
                ? parseUuid(event.getAggregateId())
                : null;
        String typeName = consentId == null
                ? null
                : consents.find(consentId)
                        .map(view ->
                                types.get(view.consent().getConsentTypeId()).getNameRu())
                        .orElse(null);
        return new HistoryEntry(
                formats.dateTime(event.getOccurredAt()),
                event.getEventType().nameRu(),
                description(event, consentId, typeName),
                event.getActorType().nameRu()
                        + (event.getActorId() == null || event.getActorId().isBlank()
                                ? ""
                                : " · " + event.getActorId()),
                consentId);
    }

    /**
     * Описание события ленты (UI-4): «Получено согласие «Реклама по email», источник — личный кабинет»,
     * «Отозвано по звонку в колл-центр, обращение — ОБР-2026/17».
     *
     * <p>Раньше строка сводилась к «тип события: название типа согласия»: ни источника получения, ни
     * источника обращения при отзыве в ленте не было, хотя §16 приводит их прямо в примерах.
     */
    private String description(ru.example.inconsensu.audit.domain.AuditEvent event, UUID consentId, String typeName) {
        String base = event.getEventType().nameRu();
        if (typeName == null) {
            return base;
        }
        String title = base + " «" + typeName + "»";
        Optional<ConsentQueryService.ConsentView> found =
                consentId == null ? Optional.empty() : consents.find(consentId);
        if (found.isEmpty()) {
            return title;
        }
        var consent = found.get().consent();
        return switch (event.getEventType()) {
            case REVOKED -> title
                    + (consent.getRevocationSource() == null
                            ? ""
                            : ", источник обращения — "
                                    + consent.getRevocationSource().nameRu().toLowerCase(java.util.Locale.ROOT))
                    + (consent.getRevocationReason() == null
                                    || consent.getRevocationReason().isBlank()
                            ? ""
                            : ", причина: " + consent.getRevocationReason());
            case SUPERSEDED -> title + ", заменено новым согласием";
            default -> title + ", источник — "
                    + consent.getSource().nameRu().toLowerCase(java.util.Locale.ROOT)
                    + (consent.getSourceRef() == null || consent.getSourceRef().isBlank()
                            ? ""
                            : " " + consent.getSourceRef());
        };
    }

    /** Идентификатор агрегата — строка: у формы или настроек это не UUID. */
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Границы фильтра по периоду в таймзоне оператора: даты в форме — календарные (UI-0.4). */
    public Instant startOfDay(java.time.LocalDate date) {
        return date == null ? null : formats.startOfDay(date);
    }

    public Instant startOfNextDay(java.time.LocalDate date) {
        return date == null ? null : formats.startOfDay(date.plusDays(1));
    }

    /** UI-4: проверка цепочки событий клиента с указанием первого нарушенного (FR-10.3). */
    @Transactional(readOnly = true)
    public AuditIntegrityService.Report verifySubjectHistory(UUID subjectId) {
        return integrity.verifySubject(subjectId);
    }

    @Transactional(readOnly = true)
    public ConsentQueryService.ConsentView consent(UUID consentId) {
        return consents.get(consentId);
    }

    /**
     * Название отзываемого согласия для диалога UI-5.
     *
     * <p>Диалог печатал идентификатор: «Отзываем: 6f1c1c9e-…». Сотрудник по такой строке не может
     * убедиться, что отзывает именно то согласие.
     */
    @Transactional(readOnly = true)
    public String consentTitle(UUID consentId) {
        return titleOf(consents.get(consentId));
    }

    /** UI-4a: сведения о согласии — тип и субъект по идентификаторам, даты в формате UI-0.4. */
    @Transactional(readOnly = true)
    public DossierSummary dossierSummary(ConsentEvidenceService.Dossier dossier) {
        var consent = dossier.consent();
        Subject subject = subjects.get(consent.getSubjectId());
        var view = consents.view(consent);
        return new DossierSummary(
                titleOf(view),
                subject.getFullName(),
                subject.getId(),
                view.statusText(),
                formats.dateTime(consent.getGrantedAt()),
                formats.validUntil(consent.getValidUntil()),
                consent.getSource().nameRu()
                        + (consent.getSourceRef() == null
                                        || consent.getSourceRef().isBlank()
                                ? ""
                                : " " + consent.getSourceRef()),
                consent.getSignatureType().nameRu(),
                consent.getRevokedAt() == null ? "" : formats.dateTime(consent.getRevokedAt()),
                consent.getRevocationSource() == null
                        ? ""
                        : consent.getRevocationSource().nameRu(),
                consent.getRevocationReason() == null ? "" : consent.getRevocationReason());
    }

    /** UI-4: согласия, которые можно отозвать сейчас, — для выбора в диалоге из шапки карточки. */
    @Transactional(readOnly = true)
    public List<RevocableConsent> revocableConsents(UUID subjectId) {
        return consents.cardConsentsOf(subjectId).stream()
                .filter(view -> view.status() == ConsentStatus.ACTIVE || view.status() == ConsentStatus.EXPIRING)
                .map(view -> new RevocableConsent(view.consent().getId(), titleOf(view)))
                .toList();
    }

    private String titleOf(ConsentQueryService.ConsentView view) {
        var consent = view.consent();
        String title = types.get(consent.getConsentTypeId()).getNameRu();
        if (consent.getThirdPartyId() != null) {
            title += " — " + thirdPartyName(consent.getThirdPartyId());
        }
        String categories = categoryNames(consent.getPdnCategories());
        return categories.isBlank() ? title : title + ": " + categories;
    }

    /** Категории ПДн по-русски: в согласии они хранятся кодами справочника (UI-0.4). */
    private String categoryNames(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        Map<String, String> names = pdnCategories.activeCategories().stream()
                .collect(Collectors.toMap(
                        ru.example.inconsensu.common.domain.PdnCategory::getCode,
                        ru.example.inconsensu.common.domain.PdnCategory::getNameRu,
                        (first, second) -> first));
        return codes.stream().map(code -> names.getOrDefault(code, code)).collect(Collectors.joining(", "));
    }

    @Transactional(readOnly = true)
    public List<ConsentRow> previewCascade(UUID consentId) {
        return revocation.previewCascade(consentId).stream()
                .map(consents::view)
                .map(this::row)
                .toList();
    }

    /** UI-0.10: раскрытие контакта — операция с ПДн, поэтому проверяется право и пишется журнал. */
    @Transactional
    public ContactView revealContact(UUID subjectId, ContactType type) {
        if (!ContactAccessPolicy.seesFullContacts(CurrentUser.roles())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "У вашей роли нет права видеть контакты клиента");
        }
        Subject subject = subjects.get(subjectId);
        SubjectContact contact = subject.getContacts().stream()
                .filter(candidate -> candidate.getType() == type)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Контакт не найден"));
        pdnAccessLog.recordSingle("/ui/subjects/{id}/reveal", subjectId);
        return new ContactView(type, type.nameRu(), formatted(type, contact.getValue()), false);
    }

    /** Сообщение после отзыва: дата, время и номер обращения — как требует UI-5. */
    public String revocationMessage(List<RevocationService.RevocationResult> results) {
        if (results.isEmpty()) {
            return "Отзывать нечего: действующих согласий не найдено";
        }
        RevocationService.RevocationResult first = results.get(0);
        long total = results.stream().mapToLong(result -> result.all().size()).sum();
        return "Согласие отозвано %s, обращение — %s. Погашено согласий: %d"
                .formatted(formats.dateTime(first.revokedAt()), first.caseNumber(), total);
    }

    private List<TransferRow> transfers(List<TransferEvaluator.TransferPermission> permissions) {
        return permissions.stream()
                .map(permission -> new TransferRow(
                        permission.thirdPartyName(),
                        permission.thirdPartyRole(),
                        categoryNames(permission.allowedCategories()),
                        formats.validUntil(permission.validUntil()),
                        permission.daysLeft() == null ? "бессрочно" : String.valueOf(permission.daysLeft()),
                        permission.basisConsentId(),
                        permission.contractExpired()))
                .toList();
    }

    private ConsentRow row(ConsentQueryService.ConsentView view) {
        var consent = view.consent();
        var type = types.get(consent.getConsentTypeId());
        return new ConsentRow(
                view,
                type.getNameRu(),
                consent.getThirdPartyId() == null ? null : thirdPartyName(consent.getThirdPartyId()),
                categoryNames(consent.getPdnCategories()),
                formats.date(consent.getGrantedAt()),
                formats.validUntil(consent.getValidUntil()),
                consent.getSource().nameRu()
                        + (consent.getSourceRef() == null
                                        || consent.getSourceRef().isBlank()
                                ? ""
                                : " " + consent.getSourceRef()),
                view.status() == ConsentStatus.ACTIVE || view.status() == ConsentStatus.EXPIRING,
                consent.getThirdPartyId() != null
                        && thirdParties.get(consent.getThirdPartyId()).isContractExpired(thirdParties.today()));
    }

    private String thirdPartyName(UUID thirdPartyId) {
        return thirdParties.get(thirdPartyId).getName();
    }

    /**
     * Причина запрета с датой: «согласие отозвано 02.06.2026», «истекло 01.05.2026» (UI-4).
     *
     * <p>Без даты сотрудник не может ответить клиенту, когда именно тот отказался, и вынужден лезть в
     * историю; §16 требует дату прямо в подписи плитки.
     */
    private String reasonWithDate(ChannelDecision decision) {
        if (decision == null) {
            return "нет согласия";
        }
        String reason = ChannelSummaryComposer.reasonText(decision.reason());
        var blocking = decision.blocking();
        if (blocking == null) {
            return reason;
        }
        return switch (decision.reason()) {
            case REVOKED -> blocking.revokedAt() == null
                    ? reason
                    : "согласие отозвано " + formats.date(blocking.revokedAt());
            case EXPIRED -> blocking.validUntil() == null ? reason : "истекло " + formats.date(blocking.validUntil());
            default -> reason;
        };
    }

    private List<ChannelTile> tiles(List<ChannelDecision> decisions) {
        Map<CommunicationChannel, ChannelDecision> byChannel =
                decisions.stream().collect(Collectors.toMap(ChannelDecision::channel, decision -> decision));
        return java.util.Arrays.stream(CommunicationChannel.values())
                .map(channel -> {
                    ChannelDecision decision = byChannel.get(channel);
                    boolean allowed = decision != null && decision.allowed();
                    return new ChannelTile(
                            channel,
                            channel.nameRu(),
                            allowed,
                            allowed && decision.basis() != null
                                    ? formats.validUntil(decision.basis().validUntil())
                                    : "",
                            reasonWithDate(decision));
                })
                .toList();
    }

    /**
     * Индикаторы каналов в строке результата (UI-3).
     *
     * <p>Карточка строится по уже загруженному субъекту: перегрузка по идентификатору перечитывает его и
     * пишет запись в журнал доступа к ПДн, из-за чего один поиск оставлял там не одну запись, как требует
     * FR-5.2, а одну плюс по записи на каждую найденную строку.
     */
    private Map<CommunicationChannel, Boolean> channelFlags(Subject subject) {
        return cards.cardOf(subject).channels().stream()
                .collect(
                        Collectors.toMap(ChannelDecision::channel, ChannelDecision::allowed, (first, second) -> first));
    }

    private List<ContactView> contactsOf(Subject subject, boolean fullContacts) {
        return subject.getContacts().stream()
                .map(contact -> new ContactView(
                        contact.getType(),
                        contact.getType().nameRu(),
                        fullContacts
                                ? formatted(contact.getType(), contact.getValue())
                                : ContactMasker.mask(contact.getType(), contact.getValue()),
                        !fullContacts))
                .toList();
    }

    private String formatted(ContactType type, String value) {
        return type == ContactType.PHONE ? formats.phone(value) : value;
    }

    private static long count(List<ConsentQueryService.ConsentView> consents, ConsentStatus status) {
        return consents.stream().filter(view -> view.status() == status).count();
    }
}
