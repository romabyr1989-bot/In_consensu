package ru.example.cus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.example.cus.support.AbstractIntegrationTest;

/**
 * §4, §11: {@code docs/openapi.yaml} is a build artefact that is committed, and it must never drift from the code.
 *
 * <p>Regenerate with {@code make openapi} (or {@code ./mvnw verify -Dcus.openapi.update=true}) and commit the result.
 */
class OpenApiSpecificationIT extends AbstractIntegrationTest {

    private static final Path SPECIFICATION = Path.of("docs", "openapi.yaml");
    private static final String UPDATE_FLAG = "cus.openapi.update";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void committed_specification_matches_the_generated_one() throws IOException {
        String generated = normalize(download());

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.createDirectories(SPECIFICATION.getParent());
            Files.writeString(SPECIFICATION, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SPECIFICATION)
                .as("docs/openapi.yaml отсутствует — выполните `make openapi` и закоммитьте результат")
                .exists();
        assertThat(normalize(Files.readString(SPECIFICATION, StandardCharsets.UTF_8)))
                .as("docs/openapi.yaml разошёлся с кодом — выполните `make openapi` и закоммитьте результат")
                .isEqualTo(generated);
    }

    private String download() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/v3/api-docs.yaml", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static String normalize(String specification) {
        return specification.replace("\r\n", "\n").strip() + "\n";
    }
}
