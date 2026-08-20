package ru.example.inconsensu.ui.application;

import java.util.Map;
import org.springframework.data.domain.Sort;

/**
 * Сортировка таблиц интерфейса (UI-0.8).
 *
 * <p>Имя поля приходит из адресной строки, поэтому оно не подставляется в запрос как есть: каждый экран
 * объявляет свой список разрешённых колонок, а всё прочее возвращает сортировку по умолчанию. Иначе
 * параметр из URL попадал бы прямо в JPQL.
 */
public final class UiSorting {

    /** Размеры страницы из UI-0.8; всё остальное приводится к первому. */
    private static final int[] PAGE_SIZES = {20, 50, 100};

    private UiSorting() {}

    public static Sort of(String field, String direction, Map<String, String> allowed, Sort fallback) {
        String property = field == null ? null : allowed.get(field);
        if (property == null) {
            return fallback;
        }
        return Sort.by(descending(direction) ? Sort.Direction.DESC : Sort.Direction.ASC, property);
    }

    public static boolean descending(String direction) {
        return "desc".equalsIgnoreCase(direction);
    }

    /** Размер страницы: 20, 50 или 100 (UI-0.8). */
    public static int pageSize(int requested) {
        for (int size : PAGE_SIZES) {
            if (size == requested) {
                return size;
            }
        }
        return PAGE_SIZES[0];
    }

    public static int[] pageSizes() {
        return PAGE_SIZES.clone();
    }
}
