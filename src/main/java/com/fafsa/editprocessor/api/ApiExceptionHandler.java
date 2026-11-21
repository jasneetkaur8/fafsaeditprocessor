package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.IncompleteApplicationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.Arrays;
import java.util.stream.Collectors;
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
 * Returns RFC 7807 problem details, and says which field was wrong when the body
 * cannot be bound to the request object.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, describe(ex));
        problem.setTitle("Malformed application");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Unknown URL. Spring's default detail repeats the path, so give a generic one instead. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.NOT_FOUND, request);
    }

    /** Required information is missing: 400 listing every missing field, not just the first. */
    @ExceptionHandler(IncompleteApplicationException.class)
    ProblemDetail handleIncompleteApplication(IncompleteApplicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Incomplete application");
        problem.setProperty("missingFields", ex.getMissingFields());
        return problem;
    }

    private static String describe(HttpMessageNotReadableException ex) {
        UnrecognizedPropertyException unknown = findCause(ex, UnrecognizedPropertyException.class);
        if (unknown != null) {
            return "Unknown field '" + fieldPath(unknown) + "'";
        }
        MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
        if (mismatch != null) {
            String path = fieldPath(mismatch);
            if (path.isEmpty()) {
                // Top-level problem: an array or scalar instead of an object, or trailing content
                return "Request body must be a single JSON object";
            }
            Class<?> target = mismatch.getTargetType();
            if (target != null && target.isEnum()) {
                return "Invalid value for field '" + path + "': expected one of " + allowedValues(target);
            }
            return "Invalid value for field '" + path + "'"
                    + (target != null ? ": expected " + target.getSimpleName() : "");
        }
        JsonProcessingException parse = findCause(ex, JsonProcessingException.class);
        if (parse != null) {
            // getOriginalMessage() omits the source snippet, so request content is not echoed back
            return "Request body is not valid JSON: " + parse.getOriginalMessage();
        }
        return "Request body is missing or unreadable";
    }

    /** An enum's JSON values (its {@code toString()}, which our enums align with {@code @JsonValue}). */
    private static String allowedValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }

    /** First exception of the given type in the cause chain (Jackson nests date-parse errors). */
    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String fieldPath(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
    }
}
