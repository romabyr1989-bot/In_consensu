package ru.example.inconsensu.integration.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный разбор CSV по RFC 4180 для импорта согласий (FR-4.5).
 *
 * <p>Своя реализация вместо библиотеки: правила формата умещаются в полсотни строк, а новая зависимость по
 * §14.8 требует ADR и проверки лицензии ради разбора семнадцати колонок.
 *
 * <p>Поддерживается то, что реально встречается в выгрузках: кавычки вокруг значения, удвоенная кавычка
 * внутри значения, разделитель и перевод строки внутри кавычек, CRLF и BOM в начале файла.
 */
public final class CsvParser {

    private static final char QUOTE = '"';
    private static final char DEFAULT_DELIMITER = ',';
    private static final char BOM = '﻿';

    private CsvParser() {}

    /** Разбирает файл с заголовком в список строк «колонка → значение». */
    public static List<Map<String, String>> parseWithHeader(String content) {
        List<List<String>> rows = parse(content, detectDelimiter(content));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0).stream()
                .map(name -> name.trim().toLowerCase(java.util.Locale.ROOT))
                .toList();

        List<Map<String, String>> result = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> cells = rows.get(index);
            if (cells.size() == 1 && cells.get(0).isBlank()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), column < cells.size() ? cells.get(column) : "");
            }
            result.add(row);
        }
        return result;
    }

    /** Выгрузки из 1С и Excel нередко приходят с точкой с запятой — определяем разделитель по заголовку. */
    static char detectDelimiter(String content) {
        int lineEnd = content.indexOf('\n');
        String header = lineEnd < 0 ? content : content.substring(0, lineEnd);
        return header.chars().filter(symbol -> symbol == ';').count()
                        > header.chars()
                                .filter(symbol -> symbol == DEFAULT_DELIMITER)
                                .count()
                ? ';'
                : DEFAULT_DELIMITER;
    }

    static List<List<String>> parse(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        String text = content.charAt(0) == BOM ? content.substring(1) : content;

        List<String> currentRow = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);

            if (inQuotes) {
                if (symbol == QUOTE) {
                    boolean escapedQuote = index + 1 < text.length() && text.charAt(index + 1) == QUOTE;
                    if (escapedQuote) {
                        value.append(QUOTE);
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    value.append(symbol);
                }
                continue;
            }

            switch (symbol) {
                case QUOTE -> inQuotes = true;
                case '\r' -> {
                    /* CRLF: перевод строки обработается следующим символом */
                }
                case '\n' -> {
                    currentRow.add(value.toString().trim());
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    value.setLength(0);
                }
                default -> {
                    if (symbol == delimiter) {
                        currentRow.add(value.toString().trim());
                        value.setLength(0);
                    } else {
                        value.append(symbol);
                    }
                }
            }
        }

        if (value.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(value.toString().trim());
            rows.add(currentRow);
        }
        return rows;
    }
}
