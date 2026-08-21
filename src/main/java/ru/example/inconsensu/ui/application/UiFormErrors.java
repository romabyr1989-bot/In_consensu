package ru.example.inconsensu.ui.application;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.common.error.ApiException;

/**
 * Ошибки формы для экрана (UI-0.9).
 *
 * <p>Отказ сервера показывался одной строкой сверху, и сотрудник не видел, какое поле не принято. Здесь
 * ошибки раскладываются по полям: сводка остаётся сверху, а подпись появляется под самим полем.
 *
 * <p>Тексты берутся из {@link ApiException} как есть: значения полей туда не попадают, потому что в них
 * могут быть персональные данные (NFR-3).
 */
public final class UiFormErrors {

    /** Имя атрибута с картой «поле → сообщение»; шаблоны читают его под этим именем. */
    public static final String ATTRIBUTE = "fieldErrors";

    private UiFormErrors() {}

    /** Кладёт сводку и ошибки полей во flash, чтобы они пережили переход после POST. */
    public static void report(RedirectAttributes redirect, ApiException exception) {
        redirect.addFlashAttribute("flashError", exception.getMessage());
        Map<String, String> byField = new LinkedHashMap<>();
        exception.errors().forEach(error -> byField.putIfAbsent(shortName(error.field()), error.message()));
        if (!byField.isEmpty()) {
            redirect.addFlashAttribute(ATTRIBUTE, byField);
        }
    }

    /**
     * Имя поля формы из имени поля запроса.
     *
     * <p>Валидатор называет поля путями вида {@code items[0].purposes} или {@code evidence.documentRef};
     * форме нужен тот кусок, который стоит в атрибуте {@code name}.
     */
    private static String shortName(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        int dot = field.lastIndexOf('.');
        return dot < 0 ? field : field.substring(dot + 1);
    }
}
