package ru.example.inconsensu.registry.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.example.inconsensu.common.domain.SignatureType;

/**
 * Обязательный состав доказательств по типу подписи (FR-4.2, таблица §7.4).
 *
 * <p>Доказательство — единственное, чем оператор подтверждает наличие согласия (ч. 3 ст. 9 152-ФЗ), поэтому
 * неполный набор полей отклоняется при регистрации, а не всплывает через год при проверке.
 */
public final class EvidenceValidator {

    /** Поля, которые нельзя записывать в журналы и тексты ошибок: это ПДн или секреты (NFR-3). */
    private static final List<String> SENSITIVE_FIELDS = List.of("phone", "otpHash", "ip", "userAgent");

    private EvidenceValidator() {}

    public static List<String> requiredFields(SignatureType signatureType) {
        return switch (signatureType) {
            case SIMPLE_ES_SMS -> List.of("phone", "otpVerifiedAt", "ip", "userAgent");
            case SIMPLE_ES_LK -> List.of("accountId", "authMethod", "actionAt", "ip", "userAgent");
            case HANDWRITTEN -> List.of("documentRef", "documentDate", "receivedByUserId");
            case UKEP -> List.of("signatureRef", "certificateSerial", "signedAt");
            case IMPORTED_LEGACY -> List.of("importJobId");
        };
    }

    /** Возвращает список недостающих полей; пустой список означает, что доказательство полное. */
    public static List<String> missingFields(SignatureType signatureType, Map<String, Object> evidence) {
        Map<String, Object> values = evidence == null ? Map.of() : evidence;
        List<String> missing = new ArrayList<>();

        for (String field : requiredFields(signatureType)) {
            if (isBlank(values.get(field))) {
                missing.add(field);
            }
        }

        // SMS-подпись: подтверждением служит либо хеш кода, либо ссылка на провайдера — нужен хотя бы один.
        if (signatureType == SignatureType.SIMPLE_ES_SMS
                && isBlank(values.get("otpHash"))
                && isBlank(values.get("providerRef"))) {
            missing.add("otpHash|providerRef");
        }
        // Импорт: основанием может быть ссылка на скан либо текстовое примечание.
        if (signatureType == SignatureType.IMPORTED_LEGACY
                && isBlank(values.get("documentRef"))
                && isBlank(values.get("note"))) {
            missing.add("documentRef|note");
        }
        return missing;
    }

    /** Копия доказательства без чувствительных значений — для журналов и отладки (NFR-3). */
    public static Map<String, Object> withoutSensitiveValues(Map<String, Object> evidence) {
        if (evidence == null) {
            return Map.of();
        }
        Map<String, Object> safe = new java.util.LinkedHashMap<>(evidence);
        SENSITIVE_FIELDS.forEach(field -> safe.computeIfPresent(field, (key, value) -> "***"));
        return safe;
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }
}
