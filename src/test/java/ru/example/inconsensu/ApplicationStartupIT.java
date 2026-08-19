package ru.example.inconsensu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.inconsensu.support.AbstractIntegrationTest;

/** Stage 0 acceptance (§13): the application starts, reports UP and serves the API documentation. */
class ApplicationStartupIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void health_endpoint_reports_the_service_and_the_database_as_up() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").contains("\"db\"");
    }

    @Test
    void prometheus_endpoint_is_exposed() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void swagger_ui_and_api_docs_are_reachable() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate
                        .getForEntity("/swagger-ui/index.html", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void documented_swagger_address_leads_to_the_ui() {
        // §13 acceptance and README promise /swagger-ui.html; springdoc redirects it to the real page.
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Swagger UI");
    }

    @Test
    void every_response_carries_a_correlation_id() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void protected_paths_answer_with_a_problem_detail_instead_of_an_empty_401() {
        // FR-11.2 deny by default: an anonymous caller learns nothing about which paths exist (NFR-3),
        // but still gets the error envelope of §9 with a correlation id.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/unknown", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .contains("urn:inconsensu:error:unauthorized")
                .contains("requestId");
    }
}
