package ru.example.cus.audit.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import ru.example.cus.audit.domain.PdnAccessLogEntry;
import ru.example.cus.common.api.ApiTime;

/** One row of the personal data access journal (FR-10.5). */
public record PdnAccessLogResponse(
        Long id,
        UUID userId,
        String endpoint,
        UUID subjectId,
        int subjectsCount,
        String requestId,
        OffsetDateTime occurredAt) {

    public static PdnAccessLogResponse of(PdnAccessLogEntry entry, ZoneId zone) {
        return new PdnAccessLogResponse(
                entry.getId(),
                entry.getUserId(),
                entry.getEndpoint(),
                entry.getSubjectId(),
                entry.getSubjectsCount(),
                entry.getRequestId(),
                ApiTime.at(entry.getOccurredAt(), zone));
    }
}
