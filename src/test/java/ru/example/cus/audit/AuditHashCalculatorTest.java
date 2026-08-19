package ru.example.cus.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.audit.domain.AuditHashCalculator;
import ru.example.cus.common.domain.ActorType;
import ru.example.cus.common.domain.AuditEventType;

/** FR-10.1: the digest must depend on everything that proves «кто, когда» and must not depend on formatting. */
class AuditHashCalculatorTest {

    private static final Instant MOMENT = Instant.parse("2026-08-18T09:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("6f1c1c9e-3b8e-4d1e-9a2f-0f5c8b1a2d34");

    @Test
    void same_event_always_produces_the_same_digest() {
        assertThat(hash(null, AuditEventType.GRANTED, "{\"a\":1,\"b\":2}"))
                .isEqualTo(hash(null, AuditEventType.GRANTED, "{\"a\":1,\"b\":2}"));
    }

    @Test
    void formatting_of_the_payload_does_not_change_the_digest() {
        assertThat(hash(null, AuditEventType.GRANTED, "{\"b\":2,\"a\":1}"))
                .isEqualTo(hash(null, AuditEventType.GRANTED, "{ \"a\" : 1 , \"b\" : 2 }"));
    }

    @Test
    void changing_the_payload_changes_the_digest() {
        assertThat(hash(null, AuditEventType.GRANTED, "{\"a\":1}"))
                .isNotEqualTo(hash(null, AuditEventType.GRANTED, "{\"a\":2}"));
    }

    @Test
    void changing_the_event_type_changes_the_digest() {
        // Hashing only the payload, as a literal reading of §6 would suggest, would leave this undetected.
        assertThat(hash(null, AuditEventType.GRANTED, "{\"a\":1}"))
                .isNotEqualTo(hash(null, AuditEventType.REVOKED, "{\"a\":1}"));
    }

    @Test
    void chaining_links_an_event_to_its_predecessor() {
        String first = hash(null, AuditEventType.CREATED, "{\"a\":1}");
        String second = hash(first, AuditEventType.UPDATED, "{\"a\":2}");
        String forged = hash("0".repeat(64), AuditEventType.UPDATED, "{\"a\":2}");

        assertThat(second).isNotEqualTo(forged);
    }

    @Test
    void day_hash_depends_on_the_order_of_events() {
        assertThat(AuditHashCalculator.dayHash(List.of("a", "b")))
                .isNotEqualTo(AuditHashCalculator.dayHash(List.of("b", "a")));
    }

    @Test
    void canonical_json_sorts_keys_recursively() {
        assertThat(AuditHashCalculator.canonicalJson("{\"b\":{\"d\":1,\"c\":2},\"a\":3}"))
                .isEqualTo("{\"a\":3,\"b\":{\"c\":2,\"d\":1}}");
    }

    @Test
    void empty_payload_is_rendered_as_an_empty_object() {
        assertThat(AuditHashCalculator.canonicalJson(null)).isEqualTo("{}");
        assertThat(AuditHashCalculator.toJson(Map.of())).isEqualTo("{}");
    }

    private static String hash(String prevHash, AuditEventType eventType, String payload) {
        return AuditHashCalculator.hash(
                prevHash, "consent", "c-1", SUBJECT, eventType, MOMENT, ActorType.USER, "ivanova", payload);
    }
}
