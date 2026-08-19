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

    /** @param color основной цвет, подставляется в CSS-переменную */
    public record Branding(String operatorName, String logoUrl, String color) {}

    private final OperatorSettingsService settings;

    public UiBrandingService(OperatorSettingsService settings) {
        this.settings = settings;
    }

    public Branding branding() {
        Map<String, String> values = settings.all();
        String name = values.getOrDefault("operator.name", "");
        String color = values.getOrDefault("branding.primary-color", DEFAULT_COLOR);
        return new Branding(
                name.isBlank() || "не заполнено".equals(name) ? "Центр управления согласиями" : name,
                values.getOrDefault("branding.logo-url", ""),
                color.isBlank() ? DEFAULT_COLOR : color);
    }
}
