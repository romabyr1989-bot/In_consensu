package ru.example.inconsensu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point of the CUS modular monolith (§5). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class InConsensuApplication {

    public static void main(String[] args) {
        SpringApplication.run(InConsensuApplication.class, args);
    }
}
