package ru.example.inconsensu.registry.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.registry.domain.ContactAccessPolicy;
import ru.example.inconsensu.registry.domain.ContactMasker;
import ru.example.inconsensu.registry.domain.Subject;

/** Субъект в ответе API. Контакты маскируются по роли (FR-5.1, NFR-3, Приложение A). */
public record SubjectResponse(
        UUID id,
        String externalId,
        String fullName,
        String lastName,
        String firstName,
        String middleName,
        LocalDate birthDate,
        List<ContactResponse> contacts) {

    public record ContactResponse(String type, String typeRu, String value, boolean masked, boolean primary) {}

    public static SubjectResponse of(Subject subject) {
        return of(subject, currentRoleSeesFullContacts());
    }

    public static SubjectResponse of(Subject subject, boolean fullContacts) {
        List<ContactResponse> contacts = subject.getContacts().stream()
                .map(contact -> new ContactResponse(
                        contact.getType().name(),
                        contact.getType().nameRu(),
                        fullContacts ? contact.getValue() : ContactMasker.mask(contact.getType(), contact.getValue()),
                        !fullContacts,
                        contact.isPrimary()))
                .toList();
        return new SubjectResponse(
                subject.getId(),
                subject.getExternalId(),
                subject.getFullName(),
                subject.getLastName(),
                subject.getFirstName(),
                subject.getMiddleName(),
                subject.getBirthDate(),
                contacts);
    }

    public static boolean currentRoleSeesFullContacts() {
        return ContactAccessPolicy.seesFullContacts(CurrentUser.roles());
    }
}
