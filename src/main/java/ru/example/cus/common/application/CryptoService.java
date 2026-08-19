package ru.example.cus.common.application;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import ru.example.cus.common.config.CusProperties;

/**
 * Шифрование контактов на уровне приложения (NFR-3, этап 8).
 *
 * <p>AES-256-GCM: случайный вектор инициализации на каждое значение и тег аутентичности, поэтому одинаковые
 * телефоны дают разный шифртекст, а подмена значения в базе не проходит незамеченной. Точный поиск при этом
 * возможен только по HMAC нормализованного значения — детерминированному при том же ключе.
 *
 * <p>Ключ приходит из окружения. Пока флаг выключен, компонент пропускает значения как есть: включение
 * шифрования на заполненной базе требует перешифрования по процедуре из runbook.
 */
@Component
public class CryptoService {

    /** Префикс отличает зашифрованное значение от открытого: база может содержать и то, и другое во время ротации. */
    public static final String PREFIX = "enc:v1:";

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String MAC = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final SecretKeySpec key;
    private final SecretKeySpec previousKey;
    private final SecretKeySpec macKey;

    public CryptoService(CusProperties properties) {
        CusProperties.Crypto crypto = properties.crypto();
        this.enabled = crypto.enabled();
        this.key = keyOf(crypto.key(), enabled);
        this.previousKey = keyOf(crypto.previousKey(), false);
        // Ключ HMAC выводится из основного, чтобы не заводить второй секрет в окружении.
        this.macKey = key == null ? null : new SecretKeySpec(hmacKeyMaterial(key), MAC);
    }

    public boolean isEnabled() {
        return enabled && key != null;
    }

    /** Шифрует значение; при выключенном флаге возвращает его без изменений. */
    public String encrypt(String plain) {
        if (!isEnabled() || plain == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать значение", e);
        }
    }

    /** Расшифровывает значение; открытый текст и {@code null} возвращаются как есть. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        String decrypted = tryDecrypt(payload, key);
        if (decrypted == null) {
            // Ротация ключа: часть записей ещё зашифрована предыдущим.
            decrypted = tryDecrypt(payload, previousKey);
        }
        if (decrypted == null) {
            throw new IllegalStateException("Не удалось расшифровать значение: ключ не подходит");
        }
        return decrypted;
    }

    /**
     * HMAC нормализованного значения для точного поиска (NFR-3).
     *
     * @return {@code null}, если ключ не настроен: тогда поиск идёт по открытому значению, как до этапа 8
     */
    public String searchHmac(String normalized) {
        if (macKey == null || normalized == null || normalized.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(macKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить HMAC значения", e);
        }
    }

    private String tryDecrypt(byte[] payload, SecretKeySpec candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, candidate, new GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH));
            byte[] decrypted = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKeySpec keyOf(String base64, boolean required) {
        if (base64 == null || base64.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "Шифрование включено (cus.crypto.enabled), но ключ cus.crypto.key не задан");
            }
            return null;
        }
        byte[] material = Base64.getDecoder().decode(base64.trim());
        if (material.length != KEY_LENGTH) {
            throw new IllegalStateException("Ключ шифрования должен содержать 32 байта (AES-256)");
        }
        return new SecretKeySpec(material, "AES");
    }

    /** Ключ для HMAC выводится из основного простым доменным разделением. */
    private static byte[] hmacKeyMaterial(SecretKeySpec key) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update("cus:contact-search".getBytes(StandardCharsets.UTF_8));
            return digest.digest(key.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
