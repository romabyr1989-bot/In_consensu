package ru.example.cus.common.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable error catalogue behind {@code ProblemDetail.type} (§9).
 *
 * <p>The URN is part of the published API contract: clients branch on it, so codes are never renamed (§14.9). Titles
 * and details are user facing and therefore Russian (NFR-8); they also replace the messages produced by the framework
 * itself, which are English and may quote parts of the request payload (NFR-3).
 */
public enum ErrorCode {
    VALIDATION_FAILED(
            "validation-failed",
            HttpStatus.BAD_REQUEST,
            "Запрос не прошёл валидацию",
            "Проверьте правильность заполнения полей запроса."),
    BAD_REQUEST(
            "bad-request",
            HttpStatus.BAD_REQUEST,
            "Некорректный запрос",
            "Запрос не может быть обработан: проверьте параметры и тело запроса."),
    UNAUTHORIZED(
            "unauthorized",
            HttpStatus.UNAUTHORIZED,
            "Требуется аутентификация",
            "Войдите в систему и повторите запрос."),
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "Недостаточно прав", "У вашей роли нет прав на эту операцию."),
    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Объект не найден", "Запрошенный ресурс не найден."),
    METHOD_NOT_ALLOWED(
            "method-not-allowed",
            HttpStatus.METHOD_NOT_ALLOWED,
            "Метод не поддерживается",
            "Этот HTTP-метод не поддерживается для указанного адреса."),
    CONFLICT(
            "conflict",
            HttpStatus.CONFLICT,
            "Конфликт состояния",
            "Текущее состояние объекта не позволяет выполнить операцию."),
    UNSUPPORTED_MEDIA_TYPE(
            "unsupported-media-type",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Неподдерживаемый формат",
            "Формат содержимого запроса не поддерживается."),
    PAYLOAD_TOO_LARGE(
            "payload-too-large",
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Превышен допустимый размер запроса",
            "Уменьшите размер запроса или файла и повторите."),
    TOO_MANY_REQUESTS(
            "too-many-requests",
            HttpStatus.TOO_MANY_REQUESTS,
            "Слишком много запросов",
            "Превышен лимит запросов. Повторите позже."),
    INTERNAL_ERROR(
            "internal-error",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Внутренняя ошибка сервиса",
            "Внутренняя ошибка сервиса. Сообщите администратору код запроса.");

    private static final String URN_PREFIX = "urn:cus:error:";

    private final String code;
    private final HttpStatus status;
    private final String title;
    private final String detail;

    ErrorCode(String code, HttpStatus status, String title, String detail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Safe, user facing replacement for the message the framework would have produced. */
    public String detail() {
        return detail;
    }

    public URI type() {
        return URI.create(URN_PREFIX + code);
    }

    /**
     * Mapping used to decorate problem details raised by the framework itself.
     *
     * <p>Written out explicitly rather than searched by status: 400 is shared by {@link #VALIDATION_FAILED} and
     * {@link #BAD_REQUEST}, and a lookup would hand {@code urn:cus:error:validation-failed} to every malformed
     * request - a validation error without the {@code errors[]} array that §9 promises for that code.
     */
    public static ErrorCode byStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
