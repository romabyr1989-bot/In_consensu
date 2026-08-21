package ru.example.inconsensu.integration.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.integration.domain.ImportRow;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Обработка одной строки импорта в отдельной транзакции (FR-4.5).
 *
 * <p>Вынесено в самостоятельный бин намеренно: {@code @Transactional} на методе, который сервис вызывает у
 * самого себя, не действует — вызов идёт мимо прокси, и «одна строка — одна транзакция» осталось бы
 * комментарием.
 */
@Service
public class ImportRowProcessor {

    private final SubjectService subjects;
    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final ThirdPartyService thirdParties;
    private final ConsentRegistrationService registration;

    public ImportRowProcessor(
            SubjectService subjects,
            ConsentTypeService types,
            ConsentFormService forms,
            ThirdPartyService thirdParties,
            ConsentRegistrationService registration) {
        this.subjects = subjects;
        this.types = types;
        this.forms = forms;
        this.thirdParties = thirdParties;
        this.registration = registration;
    }

    /** Результат строки в пакете: строка либо записана, либо отклонена со своей причиной (FR-4.5). */
    public record RowOutcome(ImportRow row, boolean imported, String rejectionReason) {}

    /**
     * Пакет строк одной транзакцией (NFR-1).
     *
     * <p>Построчная транзакция FR-4.5 стоит дорого: на каждую строку приходится собственный коммит, и
     * запись упиралась в сотню строк в секунду. Здесь коммит один на пакет, а изоляция строки достигается
     * иначе: строка сначала проверяется целиком — теми же правилами, что и пробный прогон, — и пишется
     * только после этого. Отклонённая строка не успевает ничего записать, поэтому не задевает соседей.
     *
     * <p>Если строка всё же сорвалась на записи, пакет откатывается целиком и вызывающий перезапускает его
     * построчно: медленно, зато с прежней гарантией «ошибка в строке не роняет файл».
     */
    @Transactional
    public List<RowOutcome> importChunk(UUID jobId, List<ImportRow> rows, boolean dryRun, ImportCache cache) {
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        for (ImportRow row : rows) {
            PreparedRow prepared;
            try {
                prepared = prepare(jobId, row, cache);
            } catch (ApiException rejected) {
                outcomes.add(new RowOutcome(row, false, rejected.getMessage()));
                continue;
            }
            if (!dryRun) {
                // Отказ на этой стадии означает, что часть строки уже записана: пакет откатывается, и
                // вызывающий пройдёт его построчно.
                write(prepared);
            }
            outcomes.add(new RowOutcome(row, true, null));
        }
        return outcomes;
    }

    /** Одна строка — одна транзакция (FR-4.5): ошибка в строке не роняет весь файл. */
    @Transactional
    public void importRow(UUID jobId, ImportRow row, boolean dryRun, ImportCache cache) {
        PreparedRow prepared = prepare(jobId, row, cache);
        if (dryRun) {
            // Проверка без записи: пробный прогон обязан находить те же ошибки, но ничего не менять.
            return;
        }
        write(prepared);
    }

    /** Строка, проверенная целиком: справочники разрешены, правила применены, писать можно. */
    private record PreparedRow(
            ImportRow row,
            ConsentType type,
            ConsentForm form,
            ConsentFormItem item,
            ThirdParty thirdParty,
            List<String> categories,
            Map<String, Object> evidence) {}

    /**
     * Проверка строки без единой записи (FR-4.5).
     *
     * <p>Вынесено отдельно, чтобы пакетная запись могла отклонить строку, ничего не тронув: только так
     * один коммит на пакет остаётся совместимым с правилом «ошибка в строке не роняет остальные».
     */
    private PreparedRow prepare(UUID jobId, ImportRow row, ImportCache cache) {
        ConsentType type = cache.type(row.consentTypeCode(), () -> types.getByCode(row.consentTypeCode()));
        if (!type.isActive()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Тип согласия деактивирован: " + row.consentTypeCode());
        }

        ConsentForm form = resolveForm(row, row.grantedAt(), cache);
        ConsentFormItem item = resolveItem(form, type);
        ThirdParty thirdParty = resolveThirdParty(row, type, cache);

        List<String> categories =
                row.pdnCategories().isEmpty() && item != null ? item.getPdnCategories() : row.pdnCategories();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("importJobId", jobId.toString());
        if (row.documentRef() != null) {
            evidence.put("documentRef", row.documentRef());
        }
        if (row.note() != null) {
            evidence.put("note", row.note());
        }
        if (row.documentRef() == null && row.note() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Для импортированного согласия нужен document_ref или note (FR-4.2)");
        }
        // Пригодность версии формы проверяется до записи: иначе отказ пришёлся бы на середину строки,
        // когда субъект уже создан.
        registration.requireImportableForm(form, row.grantedAt());
        return new PreparedRow(row, type, form, item, thirdParty, categories, evidence);
    }

    private void write(PreparedRow prepared) {
        ImportRow row = prepared.row();
        // FR-4.5: строка без контакта не должна стирать уже загруженные — контакты дополняются.
        Subject subject = subjects.upsertMerging(subjectFormOf(row));
        registration.registerImported(
                prepared.form(),
                new ConsentRegistrationService.ImportedConsent(
                        subject.getId(),
                        prepared.type().getId(),
                        prepared.form() == null ? null : prepared.form().getId(),
                        prepared.item() == null ? null : prepared.item().getId(),
                        prepared.form() == null ? null : prepared.form().getRenderedChecksum(),
                        row.source(),
                        row.sourceRef(),
                        row.grantedAt(),
                        row.validUntil(),
                        prepared.thirdParty() == null
                                ? null
                                : prepared.thirdParty().getId(),
                        prepared.categories(),
                        prepared.item() == null ? List.of() : prepared.item().getPurposes(),
                        prepared.evidence(),
                        row.idempotencyKey()));
    }

    /**
     * Версия формы для импортируемой строки (FR-2.3, FR-4.5).
     *
     * <p>Без явного номера берётся версия, действовавшая на дату согласия, — она может быть архивной.
     * Подстановка текущей опубликованной записала бы клиенту условия, которых он не видел. Пригодность
     * найденной версии проверяет регистрация: правило FR-2.3 одно на оба пути.
     */
    private ConsentForm resolveForm(ImportRow row, java.time.Instant grantedAt, ImportCache cache) {
        if (row.formCode() == null) {
            return null;
        }
        return cache.form(row.formCode(), row.formVersion(), grantedAt, () -> loadForm(row, grantedAt))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Нет опубликованной версии формы " + row.formCode()));
    }

    private ConsentForm loadForm(ImportRow row, java.time.Instant grantedAt) {
        if (row.formVersion() == null) {
            return forms.versionEffectiveAt(row.formCode(), grantedAt)
                    .or(() -> forms.publishedVersionOf(row.formCode()))
                    .orElse(null);
        }
        return forms.versionsOf(row.formCode()).stream()
                .filter(candidate -> candidate.getVersionNumber() == row.formVersion())
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Форма " + row.formCode() + " версии " + row.formVersion() + " не найдена"));
    }

    private ConsentFormItem resolveItem(ConsentForm form, ConsentType type) {
        if (form == null) {
            return null;
        }
        // Форма уже загружена и лежит в кэше прогона: перечитывать её на каждую строку незачем.
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getId().equals(type.getId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "В форме " + form.getCode() + " нет пункта с типом " + type.getCode()));
    }

    private ThirdParty resolveThirdParty(ImportRow row, ConsentType type, ImportCache cache) {
        if (row.thirdPartyInn() == null) {
            if (type.isRequiresThirdParty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Для передачи данных требуется ИНН третьего лица");
            }
            return null;
        }
        return cache.thirdParty(row.thirdPartyInn(), () -> thirdParties.getByInn(row.thirdPartyInn()))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Третье лицо с ИНН " + row.thirdPartyInn() + " не найдено"));
    }

    private SubjectService.SubjectForm subjectFormOf(ImportRow row) {
        List<SubjectService.ContactForm> contacts = new ArrayList<>();
        if (row.phone() != null) {
            contacts.add(new SubjectService.ContactForm(ContactType.PHONE, row.phone(), true));
        }
        if (row.email() != null) {
            contacts.add(new SubjectService.ContactForm(ContactType.EMAIL, row.email(), true));
        }
        return new SubjectService.SubjectForm(
                row.externalId(), row.lastName(), row.firstName(), row.middleName(), null, contacts);
    }
}
