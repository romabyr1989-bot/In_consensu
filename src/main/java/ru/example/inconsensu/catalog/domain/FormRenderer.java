package ru.example.inconsensu.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подстановка плейсхолдеров и вычисление контрольной суммы формы (FR-1.2, FR-1.6).
 *
 * <p>Канонический рендер намеренно не содержит данных субъекта: контрольная сумма должна описывать текст
 * документа, а не конкретного человека, иначе два согласия по одной версии формы дали бы разные суммы и §8.5
 * («каждое согласие хранит снимок условий») потерял бы смысл.
 */
public final class FormRenderer {

    /** Префикс суммы в API и в Приложении A: {@code sha256:9a1f…}. */
    public static final String CHECKSUM_PREFIX = "sha256:";

    /** Чем заменяются данные субъекта в каноническом рендере. */
    public static final String SUBJECT_PLACEHOLDER_FILLER = "______________";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_.]*)\\s*}}");
    private static final String SUBJECT_PREFIX = "subject.";

    private FormRenderer() {}

    /** Рендер для показа клиенту и для предпросмотра: подставляются все известные значения (FR-1.2). */
    public static String render(String body, Map<String, String> values) {
        return substitute(body, name -> values.getOrDefault(name, ""));
    }

    /** Канонический рендер без данных субъекта — основа контрольной суммы (FR-1.6). */
    public static String renderCanonical(String body, Map<String, String> operatorAndThirdPartyValues) {
        String substituted = substitute(
                body,
                name -> name.startsWith(SUBJECT_PREFIX)
                        ? SUBJECT_PLACEHOLDER_FILLER
                        : operatorAndThirdPartyValues.getOrDefault(name, ""));
        // Переносы строк и хвостовые пробелы не меняют смысл документа, но меняли бы сумму.
        return substituted.replace("\r\n", "\n").strip().replaceAll("[ \t]+\n", "\n");
    }

    public static String checksum(String canonicalText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return CHECKSUM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 требуется по FR-1.6, но недоступен", e);
        }
    }

    /** Имена плейсхолдеров, встречающихся в тексте: используется валидатором и панелью конструктора UI-8. */
    public static java.util.Set<String> placeholdersIn(String body) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body == null ? "" : body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Значения реквизитов оператора для подстановки. */
    public static Map<String, String> operatorValues(String operatorName, String operatorAddress) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("operator.name", operatorName == null ? "" : operatorName);
        values.put("operator.address", operatorAddress == null ? "" : operatorAddress);
        return values;
    }

    private static String substitute(String body, java.util.function.Function<String, String> resolver) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolver.apply(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
