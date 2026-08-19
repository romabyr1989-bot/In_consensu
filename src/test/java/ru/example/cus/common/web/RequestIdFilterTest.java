package ru.example.cus.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void should_generate_request_id_when_header_is_absent() throws Exception {
        MockHttpServletResponse response = invokeFilter(null);

        String requestId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    void should_reuse_incoming_request_id() throws Exception {
        MockHttpServletResponse response = invokeFilter("edge-42");

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("edge-42");
    }

    @Test
    void should_strip_characters_that_could_forge_log_records() {
        String sanitized = RequestIdFilter.sanitize("abc\ndef {\"level\":\"ERROR\"}");

        assertThat(sanitized).isEqualTo("abcdeflevel:ERROR");
    }

    @Test
    void should_truncate_overly_long_request_id() {
        String sanitized = RequestIdFilter.sanitize("x".repeat(200));

        assertThat(sanitized).hasSize(64);
    }

    @Test
    void should_clear_mdc_after_the_request() throws Exception {
        invokeFilter("edge-42");

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void should_expose_request_id_to_the_application_while_the_request_runs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "edge-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = RequestIdFilter.currentRequestId();

        filter.doFilter(request, response, chain);

        assertThat(seen[0]).isEqualTo("edge-42");
    }

    private MockHttpServletResponse invokeFilter(String incomingRequestId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incomingRequestId != null) {
            request.addHeader(RequestIdFilter.HEADER, incomingRequestId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
}
