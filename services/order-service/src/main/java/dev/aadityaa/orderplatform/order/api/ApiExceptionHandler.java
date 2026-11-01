package dev.aadityaa.orderplatform.order.api;

import dev.aadityaa.orderplatform.order.application.IdempotencyKeyReuseException;
import dev.aadityaa.orderplatform.order.application.OrderNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Order not found");
        return pd;
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail keyReuse(IdempotencyKeyReuseException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Idempotency key reuse");
        return pd;
    }

    /** Body-only validation failures (when the controller has no parameter-level constraints). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return createResponseEntity(validationProblem(errors), headers, status, request);
    }

    /** Spring 6.1+: used when any controller parameter carries a constraint (e.g. the header). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors()
                        .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
            } else {
                String name = String.valueOf(result.getMethodParameter().getParameterName());
                result.getResolvableErrors()
                        .forEach(e -> errors.putIfAbsent(name, e.getDefaultMessage()));
            }
        }
        return createResponseEntity(validationProblem(errors), headers, status, request);
    }

    private static ProblemDetail validationProblem(Map<String, String> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setTitle("Invalid request");
        pd.setProperty("errors", errors);
        return pd;
    }

    /** Fallback: log the real cause, return nothing internal to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        pd.setTitle("Internal error");
        return pd;
    }
}
