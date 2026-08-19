package ru.example.cus.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import ru.example.cus.audit.domain.AuditEvent;
import ru.example.cus.common.api.ApiTime;

/** One row of the audit journal as the auditor sees it (FR-10.5). */
public record AuditEventResponse(
        Long id,
        String aggregateType,
        String aggregateId,
        UUID subjectId,
        String eventType,
        String eventTypeRu,
        OffsetDateTime occurredAt,
        String actorType,
        String actorId,
        JsonNode payload,
        String prevHash,
        String hash) {

    public static AuditEventResponse of(AuditEvent event, ZoneId zone, ObjectMapper mapper) {
        JsonNode payload;
        try {
            payload = mapper.readTree(event.getPayload());
        } catch (Exception e) {
            payload = mapper.createObjectNode();
        }
        return new AuditEventResponse(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getSubjectId(),
                event.getEventType().name(),
                event.getEventType().nameRu(),
                ApiTime.at(event.getOccurredAt(), zone),
                event.getActorType().name(),
                event.getActorId(),
                payload,
                event.getPrevHash(),
                event.getHash());
    }
}
