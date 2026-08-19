package ru.example.inconsensu.ui.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.error.ProblemDetailWriter;
import ru.example.inconsensu.common.web.RequestIdFilter;

/**
 * Единая точка обработки ошибок контейнера (UI-17, ADR-0013).
 *
 * <p>Браузер получает страницу на русском с кодом обращения, машинный клиент — тот же ProblemDetail, что и
 * от остальных эндпоинтов: белую страницу Spring Boot не должен видеть ни тот, ни другой.
 */
@Controller
public class UiErrorController implements ErrorController {

    private final ProblemDetailWriter problemDetailWriter;

    public UiErrorController(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @RequestMapping("/error")
    public String handle(HttpServletRequest request, HttpServletResponse response, Model model) throws IOException {
        HttpStatus status = statusOf(request);

        if (!wantsHtml(request)) {
            problemDetailWriter.write(response, errorCodeOf(status), pathOf(request));
            return null;
        }

        model.addAttribute("requestId", RequestIdFilter.currentRequestId());
        response.setStatus(status.value());
        return switch (status) {
            case FORBIDDEN -> "ui/error/forbidden";
            case NOT_FOUND -> "ui/error/not-found";
            case UNAUTHORIZED -> "redirect:/ui/login";
            default -> "ui/error/server-error";
        };
    }

    private static boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String pathOf(HttpServletRequest request) {
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return path == null ? null : path.toString();
    }

    private static ErrorCode errorCodeOf(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.ACCESS_DENIED;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case PAYLOAD_TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE;
            case TOO_MANY_REQUESTS -> ErrorCode.TOO_MANY_REQUESTS;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
