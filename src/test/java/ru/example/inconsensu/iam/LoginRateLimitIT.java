package ru.example.inconsensu.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.support.AbstractIntegrationTest;

/**
 * FR-11.1: перебор логинов с одного адреса упирается в ограничение частоты.
 *
 * <p>Блокировка учётной записи после N неудач тут не помогает: злоумышленник пробует по одному паролю на
 * каждое имя и ни одну запись не блокирует. До сих пор такой перебор ничем не ограничивался.
 *
 * <p>Предел занижен свойствами: контекст с иными настройками Spring поднимает отдельно, поэтому счётчик
 * этого теста не мешает остальным — у них свой экземпляр лимитера.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inconsensu.security.login.max-failures-per-minute=3")
class LoginRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void guessing_different_logins_from_one_source_runs_into_the_limit() {
        for (int attempt = 0; attempt < 3; attempt++) {
            ResponseEntity<String> rejected = attempt(UUID.randomUUID().toString());
            assertThat(rejected.getStatusCode())
                    .as("неудачная попытка до предела — это обычный отказ")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> limited = attempt(UUID.randomUUID().toString());

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("urn:inconsensu:error:too-many-requests");
        // NFR-3: в ответе нет ни логина, ни пароля — только причина отказа.
        assertThat(limited.getBody()).doesNotContain("password");
    }

    private ResponseEntity<String> attempt(String login) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("login", login, "password", "не тот пароль"), String.class);
    }
}
