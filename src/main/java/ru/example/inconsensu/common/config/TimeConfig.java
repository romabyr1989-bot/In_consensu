package ru.example.inconsensu.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single source of "now" so that time dependent rules (§7.5, §7.9) stay testable. */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(InConsensuProperties properties) {
        return Clock.system(properties.timezone());
    }
}
