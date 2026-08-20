package ru.example.inconsensu.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс интеграционных тестов (§11): настоящая PostgreSQL, настоящие миграции Flyway, настоящий HTTP.
 *
 * <p>База внешняя и одна на прогон: продукт ставится на чистую операционную систему и не требует Docker,
 * поэтому его проверка тоже не поднимает контейнеров (ADR-0078). Адрес берётся из переменных окружения,
 * см. {@link TestDatabase}; порядок запуска описан в README.
 *
 * <p>{@code @AutoConfigureObservability} is required because Spring Boot Test switches metrics export off by default
 * ({@code management.defaults.metrics.export.enabled=false}); without it {@code /actuator/prometheus} would answer 404
 * in tests while working in production, and NFR-6 would go unverified.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({TestAccounts.class, TestForms.class})
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Выполняется до создания контекста: прогон должен начинаться с пустой базы, как когда её поднимал контейнер.
        TestDatabase.prepareOnce();
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
    }
}
