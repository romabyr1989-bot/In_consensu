package ru.example.cus.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись тела webhook (§7.9).
 *
 * <p>Потребитель считает HMAC-SHA256 от полученного тела своим секретом и сравнивает со значением заголовка:
 * это отличает вызов от ЦУС от постороннего запроса на тот же URL.
 */
public final class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private WebhookSignature() {}

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }
}
