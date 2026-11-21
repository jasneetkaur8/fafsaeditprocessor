package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.InvalidApplicationException;
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
 * Turns request problems into RFC 7807 problem responses (400 unless noted):
 * <ul>
 *   <li>body is not a single valid JSON object: "Malformed application"</li>
 *   <li>bad values or missing required fields: "Invalid application", listing every problem</li>
 *   <li>unknown URL: 404 without echoing the path</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body must be a single valid JSON object");
        problem.setTitle("Malformed application");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(InvalidApplicationException.class)
    ProblemDetail handleInvalidApplication(InvalidApplicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid application");
        problem.setProperty("errors", ex.getErrors());
        return problem;
    }

    /** Spring's default detail repeats the path, so give a generic one instead. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.NOT_FOUND, request);
    }
}
