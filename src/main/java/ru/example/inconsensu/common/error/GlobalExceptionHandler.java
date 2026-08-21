package ru.example.inconsensu.common.error;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.example.inconsensu.common.web.RequestIdFilter;

/**
 * Turns every failure into an RFC 9457 {@code ProblemDetail} (§4, §9).
 *
 * <p>Unexpected exceptions never expose their message to the caller: it may contain personal data or internals
 * (NFR-3). The correlation id is returned instead so support can find the stack trace in the logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROPERTY_ERRORS = "errors";
    private static final String PROPERTY_REQUEST_ID = "requestId";
    private static final String PROPERTY_TIMESTAMP = "timestamp";
    private static final String BLANK_TYPE = "about:blank";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception) {
        ErrorCode errorCode = exception.errorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), exception.getMessage());
        if (!exception.errors().isEmpty()) {
            problem.setProperty(PROPERTY_ERRORS, exception.errors());
        }
        decorate(problem, errorCode);
        LOG.warn(
                "Запрос отклонён: code={}, status={}",
                errorCode.code(),
                errorCode.status().value());
        return ResponseEntity.status(errorCode.status()).body(problem);
    }

    /**
     * FR-11.2: отказ по правам — это 403, а не внутренняя ошибка.
     *
     * <p>Проверка прав на уровне метода срабатывает уже внутри вызова контроллера, поэтому исключение доходит сюда
     * раньше, чем до фильтра Spring Security, и без этого обработчика превратилось бы в 500.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException exception) {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), errorCode.detail());
        decorate(problem, errorCode);
        LOG.warn("Отказано в доступе: roles={}", ru.example.inconsensu.common.security.CurrentUser.roles());
        return ResponseEntity.status(errorCode.status()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                errorCode.status(), "Внутренняя ошибка сервиса. Сообщите администратору код запроса.");
        decorate(problem, errorCode);
        LOG.error("Необработанная ошибка при обработке запроса", exception);
        return ResponseEntity.status(errorCode.status()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.VALIDATION_FAILED.status(), "Проверьте правильность заполнения полей запроса.");
        problem.setProperty(PROPERTY_ERRORS, toValidationErrors(exception.getBindingResult()));
        decorate(problem, ErrorCode.VALIDATION_FAILED);
        return handleExceptionInternal(exception, problem, headers, ErrorCode.VALIDATION_FAILED.status(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            replaceFrameworkDetail(problem, ErrorCode.byStatus(statusCode), exception);
            decorate(problem, ErrorCode.byStatus(statusCode));
        }
        return response;
    }

    /**
     * Swaps the message produced by Spring for the CUS one.
     *
     * <p>Framework details are English, which violates NFR-8, and some of them quote the request payload - a JSON parse
     * error happily echoes the phone number it choked on. The original text goes to the log next to the correlation id,
     * so support can still see it (NFR-3).
     */
    private static void replaceFrameworkDetail(ProblemDetail problem, ErrorCode errorCode, Exception exception) {
        if (isDecorated(problem)) {
            return;
        }
        LOG.warn(
                "Запрос отклонён фреймворком: code={}, status={}, exception={}",
                errorCode.code(),
                problem.getStatus(),
                exception.getClass().getSimpleName());
        problem.setDetail(errorCode.detail());
    }

    private static List<ValidationErrorItem> toValidationErrors(BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
                .map(error -> new ValidationErrorItem(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();
    }

    /** Adds the CUS specific envelope. Idempotent, because nested handlers may pass through the same instance. */
    private void decorate(ProblemDetail problem, ErrorCode errorCode) {
        if (isDecorated(problem)) {
            return;
        }
        if (problem.getType() == null || BLANK_TYPE.equals(problem.getType().toString())) {
            problem.setType(errorCode.type());
            problem.setTitle(errorCode.title());
        }
        problem.setProperty(PROPERTY_TIMESTAMP, OffsetDateTime.now(clock).toString());
        String requestId = RequestIdFilter.currentRequestId();
        if (requestId != null) {
            // Outside of a request (scheduled jobs, tests without the filter) there is nothing to correlate with,
            // and a null valued field in the response would only confuse clients.
            problem.setProperty(PROPERTY_REQUEST_ID, requestId);
        }
    }

    private static boolean isDecorated(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey(PROPERTY_TIMESTAMP);
    }
}
