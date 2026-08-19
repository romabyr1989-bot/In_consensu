package ru.example.cus.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests (§11): a real PostgreSQL, real Flyway migrations, real HTTP.
 *
 * <p>The container is a JVM wide singleton on purpose. Letting the JUnit extension manage it would restart PostgreSQL
 * for every test class, which multiplies the suite duration; Ryuk removes the container when the JVM exits.
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

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cus")
            .withUsername("cus")
            .withPassword("cus");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
