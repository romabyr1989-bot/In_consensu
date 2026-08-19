package ru.example.cus.catalog.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Построчное сравнение двух версий формы (FR-3.2, UI-9).
 *
 * <p>Своя реализация вместо библиотеки: §14.8 требует ADR и проверки лицензии на каждую зависимость, а
 * здесь нужен обычный LCS по строкам — юристу важно увидеть, какие строки исчезли и какие появились.
 */
public final class TextDiff {

    /** Тип строки в результате сравнения. */
    public enum Kind {
        SAME(" "),
        ADDED("+"),
        REMOVED("-");

        private final String marker;

        Kind(String marker) {
            this.marker = marker;
        }

        public String marker() {
            return marker;
        }
    }

    public record Line(Kind kind, String text) {}

    private TextDiff() {}

    public static List<Line> compare(String before, String after) {
        List<String> left = lines(before);
        List<String> right = lines(after);
        int[][] lcs = longestCommonSubsequence(left, right);

        List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            if (left.get(i).equals(right.get(j))) {
                result.add(new Line(Kind.SAME, left.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Line(Kind.REMOVED, left.get(i)));
                i++;
            } else {
                result.add(new Line(Kind.ADDED, right.get(j)));
                j++;
            }
        }
        while (i < left.size()) {
            result.add(new Line(Kind.REMOVED, left.get(i++)));
        }
        while (j < right.size()) {
            result.add(new Line(Kind.ADDED, right.get(j++)));
        }
        return result;
    }

    /** Есть ли различия: пустой diff означает, что версии текстуально совпадают. */
    public static boolean hasChanges(List<Line> diff) {
        return diff.stream().anyMatch(line -> line.kind() != Kind.SAME);
    }

    private static int[][] longestCommonSubsequence(List<String> left, List<String> right) {
        int[][] lcs = new int[left.size() + 1][right.size() + 1];
        for (int i = left.size() - 1; i >= 0; i--) {
            for (int j = right.size() - 1; j >= 0; j--) {
                lcs[i][j] = left.get(i).equals(right.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        return lcs;
    }

    private static List<String> lines(String text) {
        return text == null ? List.of() : List.of(text.split("\n", -1));
    }
}
