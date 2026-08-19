package ru.example.cus.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Почтовая заглушка для тестов (FR-9.2).
 *
 * <p>Контейнер один на всю JVM: письмо проверяется в нескольких сценариях, а поднимать Mailpit заново на
 * каждый класс — минуты впустую.
 */
public final class Mailpit {

    private static final int SMTP_PORT = 1025;
    private static final int HTTP_PORT = 8025;

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    DockerImageName.parse("axllent/mailpit:v1.21"))
            .withExposedPorts(SMTP_PORT, HTTP_PORT)
            .withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "1")
            .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "1")
            .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT));

    static {
        CONTAINER.start();
    }

    private Mailpit() {}

    /** Подключает приложение к заглушке и включает отправку писем, выключенную в тестовом профиле. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", CONTAINER::getHost);
        registry.add("spring.mail.port", () -> CONTAINER.getMappedPort(SMTP_PORT));
        registry.add("cus.notifications.mail.enabled", () -> true);
    }

    /** Поиск писем через HTTP API Mailpit; возвращается тело ответа как есть. */
    public static String search(org.springframework.boot.test.web.client.TestRestTemplate rest, String query) {
        return rest.getForObject(
                "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(HTTP_PORT) + "/api/v1/search?query="
                        + query,
                String.class);
    }
}
