package ru.example.cus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.example.cus.notification.domain.WebhookSignature;

/** FR-9.3: подпись тела HMAC-SHA256 на секрете подписки — то, чем потребитель отличает вызов ЦУС от чужого. */
class WebhookSignatureTest {

    private static final String BODY = "{\"event\":\"consent.revoked\"}";

    @Test
    void signatureIsStableForSameSecretAndBody() {
        assertThat(WebhookSignature.sign("secret", BODY)).isEqualTo(WebhookSignature.sign("secret", BODY));
    }

    @Test
    void signatureChangesWithSecret() {
        assertThat(WebhookSignature.sign("secret", BODY)).isNotEqualTo(WebhookSignature.sign("other", BODY));
    }

    @Test
    void signatureChangesWithBody() {
        assertThat(WebhookSignature.sign("secret", BODY))
                .isNotEqualTo(WebhookSignature.sign("secret", "{\"event\":\"consent.granted\"}"));
    }

    @Test
    void signatureIsPrefixedHex() {
        String signature = WebhookSignature.sign("secret", BODY);
        assertThat(signature).startsWith("sha256=");
        assertThat(signature.substring("sha256=".length())).hasSize(64).matches("[0-9a-f]+");
    }

    /** Проверка на известном векторе RFC 4231: своя реализация не должна разойтись со стандартом. */
    @Test
    void matchesKnownVector() {
        assertThat(WebhookSignature.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
