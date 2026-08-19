package ru.example.inconsensu.ui.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.example.inconsensu.ui.application.UiFormats;

/** Форматы интерфейса берут таймзону оператора из общего {@link Clock} (§8.7, UI-0.4). */
@Configuration
public class UiFormatsConfig {

    @Bean
    public UiFormats uiFormats(Clock clock) {
        return new UiFormats(clock.getZone());
    }
}
