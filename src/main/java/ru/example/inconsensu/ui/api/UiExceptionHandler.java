package ru.example.inconsensu.ui.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.web.RequestIdFilter;

/**
 * Ошибки экранов сотрудника показываются страницами, а не JSON (UI-0.6, UI-17).
 *
 * <p>Общий обработчик объявлен как {@code @RestControllerAdvice} и до сих пор перехватывал отказы и в
 * модуле ui: сотрудник вместо страницы «Недостаточно прав» получал в браузере тело ProblemDetail. Этот
 * обработчик ограничен пакетом экранов и объявлен с наивысшим приоритетом, поэтому срабатывает первым;
 * машинная цепочка §12 по-прежнему отвечает ProblemDetail.
 */
@ControllerAdvice(basePackages = "ru.example.inconsensu.ui.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UiExceptionHandler {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UiExceptionHandler.class);

    private final ru.example.inconsensu.ui.application.UiBrandingService branding;

    public UiExceptionHandler(ru.example.inconsensu.ui.application.UiBrandingService branding) {
        this.branding = branding;
    }

    /**
     * Брендирование добавляется здесь же: {@code @ModelAttribute} каркаса выполняется только для обычных
     * обработчиков, и страница ошибки без него падала бы на выражении в шапке.
     */
    private void common(Model model) {
        model.addAttribute("branding", branding.branding());
        model.addAttribute("requestId", RequestIdFilter.currentRequestId());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String accessDenied(Model model) {
        common(model);
        return "ui/error/forbidden";
    }

    /**
     * Прикладная ошибка: «не найдено» — своя страница, остальные — страница ошибки с кодом обращения.
     *
     * <p>Текст ошибки на страницу не выводится: он может содержать ПДн, а UI-0.6 требует показывать
     * сообщение без технических деталей и без персональных данных.
     */
    /**
     * Запрос экрана без обязательного параметра или с неразбираемым значением (UI-0.6).
     *
     * <p>Это ошибка запроса, а не сервера: сюда приводит ссылка с потерянным параметром или ручная правка
     * адреса. Отвечаем 400 и страницей с кодом обращения, в журнал — предупреждение без текста запроса.
     */
    @ExceptionHandler({
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(Exception exception, Model model) {
        LOG.warn("Запрос отклонён: {}", exception.getClass().getSimpleName());
        common(model);
        return "ui/error/server-error";
    }

    /**
     * Непредвиденная ошибка экрана: страница с кодом обращения (UI-17).
     *
     * <p>Текст исключения на страницу не выводится — в нём может быть и ПДн, и внутренние детали.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception exception, Model model) {
        LOG.error("Ошибка на экране сотрудника", exception);
        common(model);
        return "ui/error/server-error";
    }

    @ExceptionHandler(ApiException.class)
    public String apiException(ApiException exception, Model model, jakarta.servlet.http.HttpServletResponse response) {
        common(model);
        if (exception.errorCode() == ErrorCode.NOT_FOUND) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "ui/error/not-found";
        }
        if (exception.errorCode() == ErrorCode.ACCESS_DENIED) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return "ui/error/forbidden";
        }
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return "ui/error/server-error";
    }
}
