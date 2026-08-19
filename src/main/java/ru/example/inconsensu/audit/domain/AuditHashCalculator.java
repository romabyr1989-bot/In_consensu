package ru.example.inconsensu.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hash chain of the audit journal (FR-10.1).
 *
 * <p>The specification writes {@code hash = SHA-256(prev_hash + канонический payload)}. The implementation hashes the
 * whole canonical event, not only its payload: otherwise the event type, the actor and the timestamp could be edited
 * without breaking the chain, and FR-10.1 exists precisely to prove "кто, когда" (see ADR-0017).
 *
 * <p>Canonical form means: fixed field order, object keys sorted, instants in UTC with nanosecond precision, absent
 * values rendered as an empty string. Two installations hashing the same event must get the same digest.
 */
public final class AuditHashCalculator {

    /** ASCII unit separator: it cannot occur in the joined values, so "ab|c" and "a|bc" cannot collide. */
    private static final String SEPARATOR = Character.toString(0x1F);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditHashCalculator() {}

    /** Digest of an event about to be appended after {@code prevHash} ({@code null} for the first one). */
    public static String hash(
            String prevHash,
            String aggregateType,
            String aggregateId,
            UUID subjectId,
            Enum<?> eventType,
            Instant occurredAt,
            Enum<?> actorType,
            String actorId,
            String payloadJson) {
        String canonical = String.join(
                SEPARATOR,
                nullToEmpty(prevHash),
                nullToEmpty(aggregateType),
                nullToEmpty(aggregateId),
                subjectId == null ? "" : subjectId.toString(),
                eventType == null ? "" : eventType.name(),
                occurredAt == null ? "" : occurredAt.toString(),
                actorType == null ? "" : actorType.name(),
                nullToEmpty(actorId),
                canonicalJson(payloadJson));
        return sha256(canonical);
    }

    /** Digest of a whole day, used for the daily anchor (FR-10.1). */
    public static String dayHash(List<String> eventHashesInOrder) {
        return sha256(String.join(SEPARATOR, eventHashesInOrder));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by FR-10.1 but is unavailable", e);
        }
    }

    /** Serialises JSON with object keys sorted recursively, so that formatting cannot change the digest. */
    public static String canonicalJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            return canonicalize(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload журнала аудита не является корректным JSON", e);
        }
    }

    public static String toJson(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось сериализовать payload события аудита", e);
        }
    }

    private static String canonicalize(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            StringBuilder builder = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(quote(names.get(i))).append(':').append(canonicalize(node.get(names.get(i))));
            }
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(canonicalize(node.get(i)));
            }
            return builder.append(']').toString();
        }
        return node.toString();
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать имя поля payload", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
