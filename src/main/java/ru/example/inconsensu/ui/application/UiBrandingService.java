package ru.example.inconsensu.ui.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import ru.example.inconsensu.iam.application.OperatorSettingsService;

/**
 * Брендирование интерфейса из настроек оператора (UI-0.12, UI-16).
 *
 * <p>Название, логотип и основной цвет приходят из {@code operator_settings}, а не из статики: страница
 * самообслуживания должна выглядеть частью личного кабинета конкретного оператора.
 */
@Service
public class UiBrandingService {

    private static final String DEFAULT_COLOR = "#0d6efd";

    /**
     * Допустимый вид цвета: только HEX.
     *
     * <p>Значение попадает прямо в CSS страницы, поэтому произвольная строка из настроек туда идти не должна:
     * настройку правит администратор, но и он не должен уметь дописать в страницу свои правила.
     */
    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    /** @param color основной цвет, подставляется в CSS-переменную */
    public record Branding(String operatorName, String logoUrl, String color) {}

    private final OperatorSettingsService settings;

    public UiBrandingService(OperatorSettingsService settings) {
        this.settings = settings;
    }

    /** Цвет, который не жалко подставить в разметку: всё, что не HEX, заменяется умолчанием. */
    private static String safeColor(String color) {
        String trimmed = color == null ? "" : color.trim();
        return HEX_COLOR.matcher(trimmed).matches() ? trimmed : DEFAULT_COLOR;
    }

    public Branding branding() {
        Map<String, String> values = settings.all();
        String name = values.getOrDefault("operator.name", "");
        String color = values.getOrDefault("branding.primary-color", DEFAULT_COLOR);
        return new Branding(
                name.isBlank() || "не заполнено".equals(name) ? "Центр управления согласиями" : name,
                values.getOrDefault("branding.logo-url", ""),
                safeColor(color));
    }
}
