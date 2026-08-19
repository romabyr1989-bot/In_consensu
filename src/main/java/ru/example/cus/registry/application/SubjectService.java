package ru.example.cus.registry.application;

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
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.audit.application.PdnAccessLogService;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.registry.domain.ContactNormalizer;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.registry.domain.SubjectContact;
import ru.example.cus.registry.infrastructure.SubjectRepository;

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
    private final ru.example.cus.common.application.CryptoService crypto;

    public SubjectService(
            SubjectRepository repository,
            AuditService auditService,
            PdnAccessLogService pdnAccessLogService,
            ru.example.cus.common.application.CryptoService crypto) {
        this.repository = repository;
        this.auditService = auditService;
        this.pdnAccessLogService = pdnAccessLogService;
        this.crypto = crypto;
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

    private Page<Subject> doSearch(String query, Pageable pageable) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        if (trimmed.contains("@")) {
            return searchByContact(
                    ContactType.EMAIL, ContactNormalizer.normalize(ContactType.EMAIL, trimmed), pageable);
        }
        if (trimmed.startsWith("+") || trimmed.replaceAll("\\D", "").length() >= 10) {
            return searchByContact(ContactType.PHONE, ContactNormalizer.normalizePhone(trimmed), pageable);
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
            return repository.searchByFullNamePrefix(trimmed.toLowerCase(Locale.ROOT) + "%", pageable);
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

    /**
     * Создаёт субъекта или обновляет существующего по {@code external_id} (§9, FR-4.4).
     *
     * <p>Контакты заменяются целиком: источником правды по клиенту остаётся мастер-система, а не ЦУС.
     */
    @Transactional
    public Subject upsert(SubjectForm form) {
        Subject subject =
                repository.findWithContactsByExternalId(form.externalId()).orElse(null);
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
                SubjectContact contact =
                        new SubjectContact(UUID.randomUUID(), subject, form.type(), form.value(), form.primary());
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
