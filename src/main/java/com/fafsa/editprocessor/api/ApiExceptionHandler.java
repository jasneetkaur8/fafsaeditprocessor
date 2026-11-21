package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.InvalidApplicationException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns request problems into error responses:
 * <ul>
 *   <li>the application input fails its checks (bad values, missing required fields, or a body
 *       that is not a single JSON object): 400 with {@code status} INVALID_INPUT, {@code message},
 *       {@code errors} and any {@code warnings}, the same shape as other validation responses</li>
 *   <li>unknown URL: 404 problem detail, without echoing the path</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final ValidationTelemetry telemetry;

    public ApiExceptionHandler(ValidationTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        telemetry.malformedBody(ex);
        ValidationResponse body = ValidationResponse.invalidInput(
                "Request body must be a single valid JSON object", List.of(), List.of());
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(InvalidApplicationException.class)
    ResponseEntity<ValidationResponse> handleInvalidApplication(InvalidApplicationException ex) {
        // Only thrown while reading a single application; batch items are handled in the controller
        ValidationResponse body = ValidationResponse.invalidInput(ex.getMessage(), ex.getErrors(), ex.getWarnings());
        telemetry.single(body, null);
        return ResponseEntity.badRequest().body(body);
    }

    /** Spring's default detail repeats the path, so give a generic one instead. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.NOT_FOUND, request);
    }
}
