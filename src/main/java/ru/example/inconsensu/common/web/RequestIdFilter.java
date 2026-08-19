package ru.example.inconsensu.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates the correlation id of a request (§4, NFR-6).
 *
 * <p>An inbound {@code X-Request-Id} is reused, otherwise a new one is generated. The value is sanitised before it
 * reaches the logs: an attacker controlled header must never be able to inject line breaks into structured log output.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final int MAX_LENGTH = 64;
    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[^A-Za-z0-9._:-]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Runs on the ERROR dispatch as well.
     *
     * <p>Whatever handles {@code /error} - the container, Spring Security's {@code sendError}, a future error
     * controller - must report the same correlation id as the original request, otherwise support gets an id that
     * appears nowhere in the logs (NFR-6).
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static String resolveRequestId(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String alreadyAssigned && !alreadyAssigned.isBlank()) {
            return alreadyAssigned;
        }
        return sanitize(request.getHeader(HEADER));
    }

    /** Returns the current correlation id, or {@code null} outside of a request. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }

    static String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = FORBIDDEN_CHARACTERS.matcher(candidate.trim()).replaceAll("");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
