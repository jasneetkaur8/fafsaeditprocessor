package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.IncompleteApplicationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns request problems into RFC 7807 problem responses (400 unless noted):
 * <ul>
 *   <li>body cannot be bound: "Malformed application", naming the field</li>
 *   <li>required information missing (Bean Validation, or {@code ApplicationValidator} for
 *       income amounts): "Incomplete application", listing every missing field</li>
 *   <li>unknown URL: 404 without echoing the path</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "Request body is not a valid JSON application";
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            detail = (mapping instanceof UnrecognizedPropertyException ? "Unknown field '" : "Invalid value for field '")
                    + fieldPath(mapping) + "'";
        }
        return problem(ex, HttpStatus.BAD_REQUEST, "Malformed application", detail, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> missing = ex.getFieldErrors().stream().map(FieldError::getField).distinct().sorted().toList();
        return handleExceptionInternal(ex, incomplete(missing), headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(IncompleteApplicationException.class)
    ProblemDetail handleIncompleteApplication(IncompleteApplicationException ex) {
        return incomplete(ex.getMissingFields());
    }

    private static ProblemDetail incomplete(List<String> missingFields) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Missing required fields: " + String.join(", ", missingFields));
        problem.setTitle("Incomplete application");
        problem.setProperty("missingFields", missingFields);
        return problem;
    }

    /** Spring's default detail repeats the path, so give a generic one instead. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, null, "Resource not found", headers, request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, String title, String detail,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (title != null) {
            problem.setTitle(title);
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** e.g. {@code studentInfo.dateOfBirth}, or {@code list[2].name} for array elements. */
    private static String fieldPath(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
    }
}
