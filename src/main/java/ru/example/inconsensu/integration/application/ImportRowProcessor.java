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

    /** Одна строка — одна транзакция (FR-4.5): ошибка в строке не роняет весь файл. */
    @Transactional
    public void importRow(UUID jobId, ImportRow row, boolean dryRun) {
        ConsentType type = types.getByCode(row.consentTypeCode());
        if (!type.isActive()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Тип согласия деактивирован: " + row.consentTypeCode());
        }

        ConsentForm form = resolveForm(row);
        ConsentFormItem item = resolveItem(form, type);
        ThirdParty thirdParty = resolveThirdParty(row, type);

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

        if (dryRun) {
            // Проверка субъекта без записи: dry-run обязан находить те же ошибки, но ничего не менять.
            return;
        }

        // FR-4.5: строка без контакта не должна стирать уже загруженные — контакты дополняются.
        Subject subject = subjects.upsertMerging(subjectFormOf(row));
        registration.registerImported(new ConsentRegistrationService.ImportedConsent(
                subject.getId(),
                type.getId(),
                form == null ? null : form.getId(),
                item == null ? null : item.getId(),
                form == null ? null : form.getRenderedChecksum(),
                row.source(),
                row.sourceRef(),
                row.grantedAt(),
                row.validUntil(),
                thirdParty == null ? null : thirdParty.getId(),
                categories,
                item == null ? List.of() : item.getPurposes(),
                evidence,
                row.idempotencyKey()));
    }

    private ConsentForm resolveForm(ImportRow row) {
        if (row.formCode() == null) {
            return null;
        }
        if (row.formVersion() == null) {
            return forms.publishedVersionOf(row.formCode())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED, "Нет опубликованной версии формы " + row.formCode()));
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
        return forms.get(form.getId()).getItems().stream()
                .filter(item -> item.getConsentType().getId().equals(type.getId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "В форме " + form.getCode() + " нет пункта с типом " + type.getCode()));
    }

    private ThirdParty resolveThirdParty(ImportRow row, ConsentType type) {
        if (row.thirdPartyInn() == null) {
            if (type.isRequiresThirdParty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Для передачи данных требуется ИНН третьего лица");
            }
            return null;
        }
        return thirdParties.getByInn(row.thirdPartyInn());
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
