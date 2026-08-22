package ru.example.inconsensu.registry.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.audit.application.PdnAccessLogService;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.registry.domain.ContactNormalizer;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.registry.domain.SubjectContact;
import ru.example.inconsensu.registry.infrastructure.SubjectRepository;

/** Субъекты и их контакты: поиск, upsert, журнал доступа к ПДн (FR-5.2, FR-4.4, FR-10.5). */
@Service
public class SubjectService {

    public static final String AGGREGATE_TYPE = "subject";

    /** Минимальная длина запроса по ФИО (FR-5.2): иначе поиск вырождается в выгрузку базы. */
    public static final int MIN_NAME_QUERY_LENGTH = 3;

    public record ContactForm(ContactType type, String value, boolean primary) {}

    public record SubjectForm(
            String externalId,
            String lastName,
            String firstName,
            String middleName,
            LocalDate birthDate,
            List<ContactForm> contacts) {}

    private final SubjectRepository repository;
    private final AuditService auditService;
    private final PdnAccessLogService pdnAccessLogService;
    private final ru.example.inconsensu.common.application.CryptoService crypto;

    /** Часы оператора: с них проставляется начало действия контакта (§8, §8.7). */
    private final java.time.Clock clock;

    public SubjectService(
            SubjectRepository repository,
            AuditService auditService,
            PdnAccessLogService pdnAccessLogService,
            ru.example.inconsensu.common.application.CryptoService crypto,
            java.time.Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.pdnAccessLogService = pdnAccessLogService;
        this.crypto = crypto;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Subject get(UUID id) {
        Subject subject =
                repository.findWithContactsById(id).orElseThrow(() -> ApiException.notFound("Субъект не найден"));
        pdnAccessLogService.recordSingle("/api/v1/subjects/{id}", subject.getId());
        return subject;
    }

    /**
     * Поиск субъекта без записи в журнал доступа к ПДн.
     *
     * <p>Нужен внутренним сценариям (регистрация согласия, импорт): туда субъект попадает как связь, а не как
     * просмотр карточки, и запись в журнал по FR-10.5 сделала бы его нечитаемым от служебных обращений.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Subject> findByExternalId(String externalId) {
        return repository.findWithContactsByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Subject getByExternalId(String externalId) {
        Subject subject = repository
                .findWithContactsByExternalId(externalId)
                .orElseThrow(
                        () -> ApiException.notFound("Субъект с внешним идентификатором " + externalId + " не найден"));
        pdnAccessLogService.recordSingle("/api/v1/subjects/by-external-id/{externalId}", subject.getId());
        return subject;
    }

    /**
     * Единый поиск с автоопределением типа запроса, как того требует UI-3.
     *
     * <p>Каждый поиск фиксируется в журнале доступа к ПДн одной записью (FR-5.2).
     */
    @Transactional(readOnly = true)
    public Page<Subject> search(String query, Pageable pageable) {
        Page<Subject> found = doSearch(query, pageable);
        pdnAccessLogService.recordBulk(
                "/api/v1/subjects?query", (int) Math.min(found.getTotalElements(), Integer.MAX_VALUE));
        return withContacts(found);
    }

    /**
     * Поиск с панелью расширенных фильтров UI-3.
     *
     * <p>Фильтры сужают множество субъектов запросом, а не отбором готовой страницы: иначе счётчик
     * результатов и пагинация показывали бы одно, а таблица — другое.
     */
    @Transactional(readOnly = true)
    public Page<Subject> search(
            String query, SubjectFilter filter, Instant now, Instant expiringHorizon, Pageable pageable) {
        if (filter == null || filter.isEmpty()) {
            return search(query, pageable);
        }
        List<UUID> matching = repository.subjectIdsWithConsent(
                // Имя статуса, а не сам enum: Hibernate не выводит тип параметра, если тот сравнивается
                // только с литералами перечисления, и запрос падает на разборе.
                filter.status() == null ? null : filter.status().name(),
                filter.consentTypeId(),
                filter.thirdPartyId(),
                filter.source(),
                // PostgreSQL не выводит тип параметра из «? is null», поэтому дата всегда задана, а
                // применять ли её, говорит отдельный флаг.
                filter.expiringBefore() != null,
                filter.expiringBefore() == null ? Instant.EPOCH : filter.expiringBefore(),
                filter.revokedOnly(),
                now,
                expiringHorizon);
        boolean withoutQuery = query == null || query.isBlank();
        Page<Subject> found;
        if (matching.isEmpty()) {
            found = new PageImpl<>(List.of(), pageable, 0);
        } else if (withoutQuery) {
            // UI-2: плитка дашборда открывает список по одному фильтру, без поискового запроса.
            found = repository.findAllByIdIn(matching, ordered(pageable));
        } else {
            found = doSearchAmong(query, matching, pageable);
        }
        pdnAccessLogService.recordBulk(
                "/api/v1/subjects?query", (int) Math.min(found.getTotalElements(), Integer.MAX_VALUE));
        return withContacts(found);
    }

    /** @param revokedOnly «есть отозванные» — у клиента хотя бы одно отозванное согласие */
    public record SubjectFilter(
            ru.example.inconsensu.common.domain.ConsentStatus status,
            UUID consentTypeId,
            UUID thirdPartyId,
            ru.example.inconsensu.common.domain.ConsentSource source,
            Instant expiringBefore,
            boolean revokedOnly) {

        public boolean isEmpty() {
            return status == null
                    && consentTypeId == null
                    && thirdPartyId == null
                    && source == null
                    && expiringBefore == null
                    && !revokedOnly;
        }
    }

    /**
     * Поиск по явно названному признаку (§9: `GET /subjects` с `phone`, `email`, `externalId`).
     *
     * <p>Единое поле `query` с автоопределением нужно интерфейсу (UI-3), а машинному клиенту — точный
     * признак: он знает, что именно у него на руках, и не должен зависеть от эвристики. Первый
     * заполненный параметр и определяет поиск; каждый вызов пишется в журнал доступа к ПДн (FR-5.2).
     */
    @Transactional(readOnly = true)
    public Page<Subject> searchBy(String query, String phone, String email, String externalId, Pageable pageable) {
        if (phone != null && !phone.isBlank()) {
            return logged(searchByContact(ContactType.PHONE, ContactNormalizer.normalizePhone(phone), pageable));
        }
        if (email != null && !email.isBlank()) {
            return logged(searchByContact(
                    ContactType.EMAIL, ContactNormalizer.normalize(ContactType.EMAIL, email), pageable));
        }
        if (externalId != null && !externalId.isBlank()) {
            return logged(repository
                    .findByExternalId(externalId.trim())
                    .map(subject -> (Page<Subject>) new PageImpl<>(List.of(subject), pageable, 1))
                    .orElseGet(() -> new PageImpl<>(List.of(), pageable, 0)));
        }
        return search(query, pageable);
    }

    /** Каждый поиск — одна запись в журнале доступа к ПДн (FR-5.2). */
    private Page<Subject> logged(Page<Subject> found) {
        pdnAccessLogService.recordBulk(
                "/api/v1/subjects?query", (int) Math.min(found.getTotalElements(), Integer.MAX_VALUE));
        return withContacts(found);
    }

    /** Контакты догружаются одним запросом: ответ формируется уже вне транзакции (FR-5.2, UI-3). */
    private Page<Subject> withContacts(Page<Subject> found) {
        if (found.isEmpty()) {
            return found;
        }
        List<UUID> ids = found.getContent().stream().map(Subject::getId).toList();
        Map<UUID, Subject> loaded = repository.findWithContactsByIdIn(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Subject::getId, subject -> subject));
        List<Subject> ordered =
                ids.stream().map(loaded::get).filter(java.util.Objects::nonNull).toList();
        return new PageImpl<>(ordered, found.getPageable(), found.getTotalElements());
    }

    /** Тот же разбор запроса, что и в {@link #doSearch}, но в пределах отфильтрованного множества (UI-3). */
    private Page<Subject> doSearchAmong(String query, List<UUID> ids, Pageable pageable) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        if (trimmed.contains("@")) {
            return searchByContactAmong(
                    ContactType.EMAIL, ContactNormalizer.normalize(ContactType.EMAIL, trimmed), ids, ordered(pageable));
        }
        if (trimmed.startsWith("+") || trimmed.replaceAll("\\D", "").length() >= 10) {
            return searchByContactAmong(
                    ContactType.PHONE, ContactNormalizer.normalizePhone(trimmed), ids, ordered(pageable));
        }
        Optional<Subject> byExternalId = repository.findByExternalId(trimmed);
        if (byExternalId.isPresent()) {
            return ids.contains(byExternalId.get().getId())
                    ? new PageImpl<>(List.of(byExternalId.get()), pageable, 1)
                    : new PageImpl<>(List.of(), pageable, 0);
        }
        if (trimmed.matches(".*\\p{L}.*")) {
            if (trimmed.length() < MIN_NAME_QUERY_LENGTH) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Для поиска по ФИО введите не менее " + MIN_NAME_QUERY_LENGTH + " символов");
            }
            return repository.searchByFullNamePrefixAmong(
                    trimmed.toLowerCase(Locale.ROOT) + "%", ids, ordered(pageable));
        }
        return new PageImpl<>(List.of(), pageable, 0);
    }

    /** Порядок списка клиентов по умолчанию: он же зашит в нативный запрос под индексом. */
    private static final org.springframework.data.domain.Sort DEFAULT_ORDER =
            org.springframework.data.domain.Sort.by("lastName", "firstName", "id");

    /** JPQL-запросы своего order by не имеют: без сортировки порядок строк не определён. */
    private static Pageable ordered(Pageable pageable) {
        return pageable.getSort().isSorted()
                ? pageable
                : org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_ORDER);
    }

    private Page<Subject> doSearch(String query, Pageable pageable) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        if (trimmed.contains("@")) {
            return searchByContact(
                    ContactType.EMAIL, ContactNormalizer.normalize(ContactType.EMAIL, trimmed), ordered(pageable));
        }
        if (trimmed.startsWith("+") || trimmed.replaceAll("\\D", "").length() >= 10) {
            return searchByContact(ContactType.PHONE, ContactNormalizer.normalizePhone(trimmed), ordered(pageable));
        }
        // Внешний идентификатор проверяется раньше ФИО: «CRM-1002345» содержит буквы, и эвристика UI-3 иначе
        // отправила бы его в префиксный поиск по фамилии. Поиск по уникальному индексу дешёвый.
        Optional<Subject> byExternalId = repository.findByExternalId(trimmed);
        if (byExternalId.isPresent()) {
            return new PageImpl<>(List.of(byExternalId.get()), pageable, 1);
        }
        if (trimmed.matches(".*\\p{L}.*")) {
            if (trimmed.length() < MIN_NAME_QUERY_LENGTH) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Для поиска по ФИО введите не менее " + MIN_NAME_QUERY_LENGTH + " символов");
            }
            // Сортировку выбирает сотрудник: нативному запросу её не передать, поэтому при явной сортировке
            // берётся его двойник на JPQL, а порядок по умолчанию по-прежнему идёт по индексу.
            String prefix = trimmed.toLowerCase(Locale.ROOT) + "%";
            return pageable.getSort().isSorted()
                    ? repository.searchByFullNamePrefixSorted(prefix, pageable)
                    : repository.searchByFullNamePrefix(prefix, pageable);
        }
        return new PageImpl<>(List.of(), pageable, 0);
    }

    /**
     * Поиск по контакту (FR-5.2).
     *
     * <p>При включённом шифровании сравнение идёт по HMAC: в колонке лежит шифртекст со случайным вектором
     * инициализации, и одинаковые телефоны выглядят в базе по-разному (NFR-3).
     */
    private Page<Subject> searchByContact(ContactType type, String normalized, Pageable pageable) {
        if (crypto.isEnabled()) {
            return repository.searchByContactHmac(type, crypto.searchHmac(normalized), pageable);
        }
        return repository.searchByContact(type, normalized, pageable);
    }

    private Page<Subject> searchByContactAmong(ContactType type, String normalized, List<UUID> ids, Pageable pageable) {
        if (crypto.isEnabled()) {
            return repository.searchByContactHmacAmong(type, crypto.searchHmac(normalized), ids, pageable);
        }
        return repository.searchByContactAmong(type, normalized, ids, pageable);
    }

    /**
     * Создаёт субъекта или обновляет существующего по {@code external_id} (§9, FR-4.4).
     *
     * <p>Контакты заменяются целиком: источником правды по клиенту остаётся мастер-система, а не In consensu.
     */
    /**
     * Обновление клиента с добавлением контактов, а не заменой (FR-4.5).
     *
     * <p>Импорт идёт построчно, и в строке может не быть телефона или email. Полная замена контактов
     * стирала бы то, что уже загружено предыдущими строками того же клиента; здесь новые контакты
     * добавляются к существующим, а совпадающие по нормализованному значению не дублируются.
     */
    @Transactional
    public Subject upsertMerging(SubjectForm form) {
        Subject existing =
                repository.findWithContactsByExternalId(form.externalId()).orElse(null);
        if (existing == null) {
            // Субъекта уже искали: повторный поиск внутри upsert удваивал число запросов на строку импорта.
            return save(null, form);
        }

        List<ContactForm> merged = new ArrayList<>();
        for (SubjectContact contact : existing.getContacts()) {
            merged.add(new ContactForm(contact.getType(), contact.getValue(), contact.isPrimary()));
        }
        java.util.Set<String> known = existing.getContacts().stream()
                .map(contact ->
                        contact.getType() + ":" + ContactNormalizer.normalize(contact.getType(), contact.getValue()))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        if (form.contacts() != null) {
            for (ContactForm candidate : form.contacts()) {
                String key = candidate.type() + ":" + ContactNormalizer.normalize(candidate.type(), candidate.value());
                if (known.add(key)) {
                    merged.add(candidate);
                }
            }
        }

        return save(
                existing,
                new SubjectForm(
                        form.externalId(),
                        form.lastName(),
                        form.firstName(),
                        form.middleName(),
                        form.birthDate(),
                        merged));
    }

    @Transactional
    public Subject upsert(SubjectForm form) {
        return save(repository.findWithContactsByExternalId(form.externalId()).orElse(null), form);
    }

    /** @param subject уже найденный субъект или {@code null}, если его нет: повторный поиск не нужен */
    private Subject save(Subject subject, SubjectForm form) {
        boolean created = subject == null;
        if (created) {
            subject = new Subject(
                    UUID.randomUUID(),
                    form.externalId(),
                    form.lastName(),
                    form.firstName(),
                    form.middleName(),
                    form.birthDate());
        } else {
            subject.rename(form.lastName(), form.firstName(), form.middleName(), form.birthDate());
        }
        subject.replaceContacts(buildContacts(subject, form.contacts()));
        Subject saved = repository.save(subject);

        // Персональные данные в журнал не попадают: только идентификаторы и состав контактов (NFR-3).
        auditService.record(
                AGGREGATE_TYPE,
                saved.getId().toString(),
                saved.getId(),
                created ? AuditEventType.CREATED : AuditEventType.UPDATED,
                Map.of(
                        "externalId", saved.getExternalId(),
                        "contactTypes",
                                saved.getContacts().stream()
                                        .map(contact -> contact.getType().name())
                                        .distinct()
                                        .toList()));
        return saved;
    }

    private List<SubjectContact> buildContacts(Subject subject, List<ContactForm> forms) {
        List<SubjectContact> contacts = new ArrayList<>();
        if (forms == null) {
            return contacts;
        }
        for (ContactForm form : forms) {
            try {
                SubjectContact contact = new SubjectContact(
                        UUID.randomUUID(), subject, form.type(), form.value(), form.primary(), clock.instant());
                // NFR-3: по шифртексту точное сравнение невозможно, поэтому рядом хранится HMAC значения.
                contact.applySearchHmac(crypto.searchHmac(ContactNormalizer.normalize(form.type(), form.value())));
                contacts.add(contact);
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
            }
        }
        return contacts;
    }

    /**
     * Разрешает список идентификаторов (UUID или внешних) в идентификаторы субъектов (FR-6.4).
     *
     * <p>Возвращает и то, что не нашлось: массовая проверка обязана честно сообщить, по каким записям ответа
     * нет, а не молча их проглотить.
     */
    @Transactional(readOnly = true)
    public ResolvedSubjects resolve(java.util.Collection<String> identifiers) {
        java.util.Map<String, UUID> found = new java.util.LinkedHashMap<>();
        java.util.List<String> unknown = new java.util.ArrayList<>();
        java.util.List<String> externalIds = new java.util.ArrayList<>();
        java.util.Map<UUID, String> byUuid = new java.util.LinkedHashMap<>();

        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            try {
                byUuid.put(UUID.fromString(identifier.trim()), identifier);
            } catch (IllegalArgumentException notUuid) {
                externalIds.add(identifier.trim());
            }
        }

        if (!byUuid.isEmpty()) {
            java.util.Set<UUID> existing = new java.util.HashSet<>(repository.findExistingIds(byUuid.keySet()));
            byUuid.forEach((id, identifier) -> {
                if (existing.contains(id)) {
                    found.put(identifier, id);
                } else {
                    unknown.add(identifier);
                }
            });
        }

        if (!externalIds.isEmpty()) {
            java.util.Map<String, UUID> byExternalId = new java.util.HashMap<>();
            repository
                    .findIdsByExternalIds(externalIds)
                    .forEach(row -> byExternalId.put((String) row[1], (UUID) row[0]));
            for (String externalId : externalIds) {
                UUID id = byExternalId.get(externalId);
                if (id == null) {
                    unknown.add(externalId);
                } else {
                    found.put(externalId, id);
                }
            }
        }
        return new ResolvedSubjects(found, unknown);
    }

    /** @param byIdentifier исходный идентификатор из запроса → идентификатор субъекта */
    public record ResolvedSubjects(java.util.Map<String, UUID> byIdentifier, java.util.List<String> unknown) {}
}
