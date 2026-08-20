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

    /**
     * Версия формы целиком: тело, обязательные блоки ч. 4 ст. 9 152-ФЗ и пункты (FR-1.5, FR-1.6).
     *
     * @param items в порядке следования в форме: перестановка пунктов меняет документ
     */
    public record CanonicalForm(
            String body, String processingActions, String revocationProcedure, java.util.List<Item> items) {

        /** Пункт формы в том виде, в каком он попадает в текст версии и в контрольную сумму. */
        public record Item(
                String consentTypeCode,
                String text,
                java.util.List<String> purposes,
                java.util.List<String> pdnCategories,
                String thirdPartyName,
                String validity,
                boolean mandatory) {}
    }

    /** Канонический рендер без данных субъекта — основа контрольной суммы (FR-1.6). */
    public static String renderCanonical(String body, Map<String, String> operatorAndThirdPartyValues) {
        return renderCanonical(new CanonicalForm(body, null, null, java.util.List.of()), operatorAndThirdPartyValues);
    }

    /**
     * Канонический рендер версии формы.
     *
     * <p>Сумма обязана покрывать документ целиком. Раньше в неё входило только тело, поэтому две версии,
     * различающиеся перечнем действий и способов обработки, порядком отзыва или составом пунктов (целями,
     * категориями ПДн, третьим лицом, сроком), давали одинаковую сумму — и §8.5 «каждое согласие хранит
     * снимок условий» переставал выполняться.
     */
    public static String renderCanonical(CanonicalForm form, Map<String, String> operatorAndThirdPartyValues) {
        StringBuilder document = new StringBuilder(text(form.body()));
        appendBlock(document, "Перечень действий и способов обработки", form.processingActions());
        appendBlock(document, "Срок действия и порядок отзыва", form.revocationProcedure());
        java.util.List<CanonicalForm.Item> items = form.items() == null ? java.util.List.of() : form.items();
        for (int index = 0; index < items.size(); index++) {
            appendItem(document, index + 1, items.get(index));
        }

        String substituted = substitute(
                document.toString(),
                name -> name.startsWith(SUBJECT_PREFIX)
                        ? SUBJECT_PLACEHOLDER_FILLER
                        : operatorAndThirdPartyValues.getOrDefault(name, ""));
        // Переносы строк и хвостовые пробелы не меняют смысл документа, но меняли бы сумму.
        return substituted.replace("\r\n", "\n").strip().replaceAll("[ \t]+\n", "\n");
    }

    private static void appendBlock(StringBuilder document, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        document.append("\n\n").append(title).append(":\n").append(value.strip());
    }

    private static void appendItem(StringBuilder document, int number, CanonicalForm.Item item) {
        document.append("\n\n")
                .append(number)
                .append(". [")
                .append(text(item.consentTypeCode()))
                .append("] ")
                .append(text(item.text()));
        appendList(document, "Цели", item.purposes());
        appendList(document, "Категории ПДн", item.pdnCategories());
        if (item.thirdPartyName() != null && !item.thirdPartyName().isBlank()) {
            document.append("\n   Третье лицо: ").append(item.thirdPartyName().strip());
        }
        document.append("\n   Срок: ")
                .append(item.validity() == null || item.validity().isBlank() ? "до отзыва" : item.validity());
        document.append("\n   Обязателен для заключения договора: ").append(item.mandatory() ? "да" : "нет");
    }

    private static void appendList(StringBuilder document, String title, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        document.append("\n   ").append(title).append(": ").append(String.join("; ", values));
    }

    private static String text(String value) {
        return value == null ? "" : value;
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
