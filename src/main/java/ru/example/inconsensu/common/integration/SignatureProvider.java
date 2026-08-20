package ru.example.inconsensu.common.integration;

import java.util.Map;
import ru.example.inconsensu.common.domain.SignatureType;

/**
 * Точка расширения для квалифицированной электронной подписи (§3).
 *
 * <p>УКЭП вне объёма работ, но §3 требует предусмотреть интерфейс: подключение криптопровайдера не должно
 * означать правку доменной логики. Проверка вызывается при регистрации согласия и отвечает, признаёт ли
 * провайдер представленные доказательства.
 */
public interface SignatureProvider {

    /** Способ подписания, который умеет проверять эта реализация. */
    SignatureType supports();

    /**
     * Результат проверки доказательств.
     *
     * @param valid признаёт ли провайдер подпись действительной
     * @param reason причина отказа, по-русски; пустая при успехе
     */
    record Verification(boolean valid, String reason) {

        public static Verification ok() {
            return new Verification(true, "");
        }

        public static Verification rejected(String reason) {
            return new Verification(false, reason);
        }
    }

    /** @param evidence поля доказательств согласия (FR-4.2) */
    Verification verify(Map<String, Object> evidence);
}
