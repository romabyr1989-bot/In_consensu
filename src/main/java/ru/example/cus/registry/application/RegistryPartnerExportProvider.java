package ru.example.cus.registry.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.domain.ContactType;
import ru.example.cus.registry.domain.Consent;
import ru.example.cus.registry.domain.Subject;
import ru.example.cus.registry.domain.SubjectContact;
import ru.example.cus.registry.infrastructure.ConsentRepository;
import ru.example.cus.registry.infrastructure.SubjectRepository;
import ru.example.cus.thirdparty.domain.PartnerExportDataPort;

/**
 * Собирает выгрузку для партнёра (FR-7.4).
 *
 * <p>Фильтрация по категориям выполняется здесь, у владельца данных: наружу из модуля не должно выйти ни
 * одного значения, которое партнёру передавать нельзя (NFR-3).
 */
@Component
public class RegistryPartnerExportProvider implements PartnerExportDataPort {

    private static final Map<String, ContactType> CONTACT_CATEGORIES = Map.of(
            "PHONE", ContactType.PHONE,
            "EMAIL", ContactType.EMAIL,
            "POSTAL_ADDRESS", ContactType.POSTAL_ADDRESS);

    private final ConsentRepository consents;
    private final SubjectRepository subjects;
    private final RegistryTransferConsentProvider transfers;

    public RegistryPartnerExportProvider(
            ConsentRepository consents, SubjectRepository subjects, RegistryTransferConsentProvider transfers) {
        this.consents = consents;
        this.subjects = subjects;
        this.transfers = transfers;
    }

    private boolean baseConsentUsable(UUID subjectId) {
        return transfers.baseConsentUsable(subjectId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportRow> rowsFor(UUID thirdPartyId, Set<String> allowedCategories, Instant now) {
        List<ExportRow> rows = new ArrayList<>();

        for (Consent consent : consents.findUsableByThirdParty(thirdPartyId)) {
            // Пересечение ещё раз: состав согласия мог быть уже категорий договора (FR-7.2).
            Set<String> categories = consent.getPdnCategories().stream()
                    .filter(allowedCategories::contains)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (categories.isEmpty()) {
                continue;
            }
            // §8.3 п.3: без живого базового согласия передавать нечего, даже если согласие на передачу цело.
            if (!baseConsentUsable(consent.getSubjectId())) {
                continue;
            }
            subjects.findWithContactsById(consent.getSubjectId())
                    .ifPresent(
                            subject -> rows.add(new ExportRow(subject.getExternalId(), valuesOf(subject, categories))));
        }
        return rows;
    }

    private Map<String, String> valuesOf(Subject subject, Set<String> categories) {
        Map<String, String> values = new LinkedHashMap<>();
        if (categories.contains("FIO")) {
            values.put("FIO", subject.getFullName());
        }
        if (categories.contains("BIRTH_DATE") && subject.getBirthDate() != null) {
            values.put("BIRTH_DATE", subject.getBirthDate().toString());
        }
        CONTACT_CATEGORIES.forEach((category, type) -> {
            if (categories.contains(category)) {
                subject.getContacts().stream()
                        .filter(contact -> contact.getType() == type)
                        .max(java.util.Comparator.comparing(SubjectContact::isPrimary))
                        .ifPresent(contact -> values.put(category, contact.getValue()));
            }
        });
        return values;
    }
}
