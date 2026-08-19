package ru.example.cus.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.common.web.RequestIdFilter;

/** §9: every failure is rendered as an RFC 9457 problem detail with a stable {@code urn:cus:error:*} type. */
class GlobalExceptionHandlerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(FIXED_CLOCK))
                // The filter is part of the contract: it is what puts the correlation id into the MDC (§9, NFR-6).
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void should_render_business_failure_as_problem_detail() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:cus:error:not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.timestamp").value("2026-08-17T10:00Z"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Объект не найден")
                .contains("Субъект не найден");
    }

    @Test
    void should_list_every_violated_field_in_errors_array() throws Exception {
        MvcResult result = mockMvc.perform(post("/probe/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:cus:error:validation-failed"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("externalId"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Запрос не прошёл валидацию");
    }

    @Test
    void should_not_leak_internals_of_an_unexpected_failure() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:cus:error:internal-error"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("+7 916", "sql", "IllegalStateException");
        assertThat(body).contains("Внутренняя ошибка сервиса");
    }

    @Test
    void should_map_framework_failures_to_the_error_catalogue() throws Exception {
        MvcResult result = mockMvc.perform(post("/probe/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:cus:error:method-not-allowed"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn();

        // NFR-8 + NFR-3: the English message of the framework never reaches the client.
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("Этот HTTP-метод не поддерживается для указанного адреса.");
        assertThat(body).doesNotContain("Request method", "not supported");
    }

    @Test
    void should_not_echo_the_request_body_of_an_unreadable_payload() throws Exception {
        MvcResult result = mockMvc.perform(post("/probe/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\": \"+7 916 000-00-00\" "))
                .andExpect(status().isBadRequest())
                // §9: validation-failed promises an errors[] array, so a malformed body is bad-request instead.
                .andExpect(jsonPath("$.type").value("urn:cus:error:bad-request"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();

        // A JSON parse error would otherwise quote the fragment it choked on - possibly personal data (NFR-3).
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("+7 916", "JSON parse error");
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/missing")
        String missing() {
            throw ApiException.notFound("Субъект не найден");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("phone +7 916 000-00-00 rejected by sql constraint");
        }

        @PostMapping("/subjects")
        String create(@Valid @RequestBody SubjectRequest request) {
            return request.externalId();
        }
    }

    record SubjectRequest(@NotBlank String externalId) {}
}
