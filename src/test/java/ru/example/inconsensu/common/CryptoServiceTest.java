package ru.example.inconsensu.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.application.CryptoService;
import ru.example.inconsensu.common.config.InConsensuProperties;

/** NFR-3: контакты шифруются AES-256-GCM, точный поиск идёт по HMAC нормализованного значения. */
class CryptoServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private static CryptoService service(boolean enabled, String key, String previousKey) {
        return new CryptoService(new InConsensuProperties(
                java.time.ZoneId.of("Europe/Moscow"),
                new InConsensuProperties.Security(
                        new InConsensuProperties.Jwt(
                                "", java.time.Duration.ofMinutes(60), java.time.Duration.ofDays(7), "cus"),
                        new InConsensuProperties.Login(5, java.time.Duration.ofMinutes(15), 50),
                        new InConsensuProperties.Cors(
                                java.util.List.of(), java.util.List.of(), java.util.List.of(), false),
                        true,
                        true),
                new InConsensuProperties.Bootstrap("", "", "Администратор"),
                new InConsensuProperties.Selfservice(
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(15), "'self'"),
                new InConsensuProperties.Iam(new InConsensuProperties.Oidc("", "", "realm_access.roles")),
                new InConsensuProperties.Notifications(
                        new InConsensuProperties.Webhook(
                                java.time.Duration.ofSeconds(5),
                                java.time.Duration.ofSeconds(10),
                                "X-InConsensu-Signature",
                                java.util.List.of(),
                                false),
                        new InConsensuProperties.Mail("noreply@example.ru", true),
                        "http://localhost:8080",
                        50),
                new InConsensuProperties.Crypto(enabled, key, previousKey)));
    }

    @Test
    void disabled_service_passes_values_through() {
        CryptoService crypto = service(false, "", "");

        assertThat(crypto.isEnabled()).isFalse();
        assertThat(crypto.encrypt("+79160000041")).isEqualTo("+79160000041");
        assertThat(crypto.decrypt("+79160000041")).isEqualTo("+79160000041");
    }

    @Test
    void enabled_service_hides_the_value_and_restores_it() {
        CryptoService crypto = service(true, KEY, "");

        String encrypted = crypto.encrypt("+79160000041");

        assertThat(encrypted).startsWith(CryptoService.PREFIX).doesNotContain("79160000041");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("+79160000041");
    }

    @Test
    void the_same_value_looks_different_every_time() {
        CryptoService crypto = service(true, KEY, "");

        // Случайный вектор инициализации: одинаковые телефоны нельзя сопоставить прямо в базе.
        assertThat(crypto.encrypt("+79160000041")).isNotEqualTo(crypto.encrypt("+79160000041"));
    }

    @Test
    void search_hmac_is_stable_and_key_dependent() {
        CryptoService crypto = service(true, KEY, "");
        CryptoService other = service(true, OTHER_KEY, "");

        assertThat(crypto.searchHmac("+79160000041")).isEqualTo(crypto.searchHmac("+79160000041"));
        assertThat(crypto.searchHmac("+79160000041")).isNotEqualTo(other.searchHmac("+79160000041"));
        assertThat(crypto.searchHmac("+79160000041")).doesNotContain("79160000041");
    }

    @Test
    void previous_key_keeps_data_readable_during_rotation() {
        CryptoService before = service(true, OTHER_KEY, "");
        String encryptedWithOldKey = before.encrypt("t***@example.ru");

        CryptoService afterRotation = service(true, KEY, OTHER_KEY);

        assertThat(afterRotation.decrypt(encryptedWithOldKey)).isEqualTo("t***@example.ru");
        // Новые значения шифруются новым ключом: сервис со старым ключом их уже не прочитает.
        String encryptedWithNewKey = afterRotation.encrypt("новое значение");
        assertThatThrownBy(() -> before.decrypt(encryptedWithNewKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ключ не подходит");
    }

    @Test
    void enabling_encryption_without_a_key_fails_fast() {
        assertThatThrownBy(() -> service(true, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsensu.crypto.key");
    }
}
