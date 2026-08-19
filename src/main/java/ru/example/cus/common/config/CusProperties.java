package ru.example.cus.common.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings that must be known before the database is reachable (§6, §8.7, FR-11.1).
 *
 * <p>Everything an operator may change at runtime lives in {@code operator_settings} instead (FR-11.3); this record
 * only carries start-up level knobs and secrets, which by NFR-3 come from the environment.
 */
@ConfigurationProperties(prefix = "cus")
public record CusProperties(
        @DefaultValue("Europe/Moscow") ZoneId timezone,
        @DefaultValue Security security,
        @DefaultValue Bootstrap bootstrap,
        @DefaultValue Notifications notifications,
        @DefaultValue Crypto crypto) {

    /**
     * @param publicApiDocs §9: доступ к Swagger UI и /v3/api-docs «по настройке»
     * @param publicMetrics §9: /actuator/prometheus «по настройке»; в контуре его обычно скрывают сетью
     */
    public record Security(
            @DefaultValue Jwt jwt,
            @DefaultValue Login login,
            @DefaultValue("true") boolean publicApiDocs,
            @DefaultValue("true") boolean publicMetrics) {}

    /**
     * @param secret HMAC key, at least 32 bytes. Empty means "generate a random one at start-up", which is fine for a
     *     developer machine and unacceptable in production: tokens die with the process and instances disagree.
     */
    public record Jwt(
            @DefaultValue("") String secret,
            @DefaultValue("PT60M") Duration accessTokenTtl,
            @DefaultValue("P7D") Duration refreshTokenTtl,
            @DefaultValue("cus") String issuer) {}

    /** FR-11.1: protection of {@code /auth/login} against brute force. */
    public record Login(@DefaultValue("5") int maxFailedAttempts, @DefaultValue("PT15M") Duration lockDuration) {}

    /**
     * Infrastructure knobs of stage 6. Everything an operator changes without a restart (thresholds, digest size)
     * lives in {@code operator_settings} instead (FR-11.3).
     *
     * @param baseUrl absolute address of this installation, used to build links inside e-mails
     * @param batchSize how many outbox events one processor pass takes
     */
    public record Notifications(
            @DefaultValue Webhook webhook,
            @DefaultValue Mail mail,
            @DefaultValue("http://localhost:8080") String baseUrl,
            @DefaultValue("50") int batchSize) {}

    /**
     * @param signatureHeader §7.9: consumers verify HMAC-SHA256 of the raw body with the subscription secret
     */
    /**
     * @param allowedHosts NFR-4: список разрешённых хостов подписок. Пустой список означает «без ограничений»
     *     и годится только для разработки: в контуре заказчика адрес подписки — это направление, куда уходят
     *     события, и его нельзя оставлять на усмотрение того, кто получил роль ADMIN.
     */
    public record Webhook(
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration readTimeout,
            @DefaultValue("X-Cus-Signature") String signatureHeader,
            @DefaultValue java.util.List<String> allowedHosts,
            @DefaultValue("false") boolean requireHttps) {}

    /**
     * @param from envelope sender of every notification e-mail
     * @param enabled turning it off keeps notifications in the journal but sends nothing — used by tests and by
     *     installations where e-mail is delivered by an external system
     */
    public record Mail(@DefaultValue("cus@example.ru") String from, @DefaultValue("true") boolean enabled) {}

    /**
     * NFR-3: шифрование контактов на уровне приложения (этап 8).
     *
     * @param key ключ AES-256 в base64 (32 байта). Берётся только из окружения: в конфигурации в репозитории
     *     его быть не может
     * @param previousKey предыдущий ключ; нужен во время ротации, чтобы читать ещё не перешифрованные записи
     */
    public record Crypto(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String key,
            @DefaultValue("") String previousKey) {}

    /** FR-11.1: the first administrator is created from the environment while the user table is still empty. */
    public record Bootstrap(
            @DefaultValue("") String adminLogin,
            @DefaultValue("") String adminPassword,
            @DefaultValue("Администратор") String adminFullName) {}
}
