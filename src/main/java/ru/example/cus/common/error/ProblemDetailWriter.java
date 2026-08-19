package ru.example.cus.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.example.cus.common.web.RequestIdFilter;

/**
 * Writes an {@link ErrorCode} straight to the servlet response as an RFC 9457 problem detail.
 *
 * <p>Needed where the failure happens before or outside the {@code @ControllerAdvice}: the security filter chain
 * rejects a request without ever reaching a controller, and an empty 401 body would break the error contract of §9.
 */
@Component
public class ProblemDetailWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProblemDetailWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode, String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", errorCode.type().toString());
        body.put("title", errorCode.title());
        body.put("status", errorCode.status().value());
        body.put("detail", errorCode.detail());
        if (path != null) {
            body.put("instance", path);
        }
        body.put("timestamp", OffsetDateTime.now(clock).toString());
        String requestId = RequestIdFilter.currentRequestId();
        if (requestId != null) {
            body.put("requestId", requestId);
        }

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
