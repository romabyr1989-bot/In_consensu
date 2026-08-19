package ru.example.cus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point of the CUS modular monolith (§5). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CusApplication {

    public static void main(String[] args) {
        SpringApplication.run(CusApplication.class, args);
    }
}
