package ru.example.cus.common.error;

import java.util.List;

/** Base class for expected, client visible failures that map onto a {@link ErrorCode} (§9). */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ValidationErrorItem> errors;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    public ApiException(ErrorCode errorCode, String detail, List<ValidationErrorItem> errors) {
        super(detail);
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(ErrorCode.CONFLICT, detail);
    }

    public static ApiException validation(String detail, List<ValidationErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail, errors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ValidationErrorItem> errors() {
        return errors;
    }
}
