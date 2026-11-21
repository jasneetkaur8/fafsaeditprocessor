package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.AppliedDefault;
import com.fafsa.editprocessor.input.ApplicationData;
import com.fafsa.editprocessor.input.InputError;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Metrics and log lines for each validated application. Logs and metric tags only ever hold
 * outcomes and field names, never application values (no SSN, names, dates or amounts).
 *
 * <ul>
 *   <li>{@code fafsa.validations} (counter): tags {@code mode} (single, batch) and {@code status}</li>
 *   <li>{@code fafsa.input.errors} (counter): tag {@code field}; field names the application does
 *       not define are tagged {@code unknown}, so callers cannot create unlimited metrics</li>
 *   <li>{@code fafsa.defaults.applied} (counter): tag {@code field}</li>
 *   <li>{@code fafsa.batch.size} (summary) and {@code fafsa.batch.rejected} (counter)</li>
 * </ul>
 * Rule results and timings are recorded by the engine; HTTP timings by Spring
 * ({@code http.server.requests}).
 */
@Component
public class ValidationTelemetry {

    private static final Logger log = LoggerFactory.getLogger(ValidationTelemetry.class);

    static final String SINGLE = "single";
    static final String BATCH = "batch";

    private final MeterRegistry registry;

    public ValidationTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts one application's result. */
    void record(String mode, ValidationResponse response) {
        registry.counter("fafsa.validations", "mode", mode, "status", response.status()).increment();
        for (InputError error : response.errors()) {
            String field = ApplicationData.isKnownPath(error.field()) ? error.field() : "unknown";
            registry.counter("fafsa.input.errors", "field", field).increment();
        }
        for (AppliedDefault applied : response.warnings()) {
            registry.counter("fafsa.defaults.applied", "field", applied.field()).increment();
        }
    }

    /** Counts and logs a single application's result. */
    void single(ValidationResponse response, String rulesetVersion) {
        record(SINGLE, response);
        log.info("Validated application: status={} ruleset={} violations={} errors={} defaults={}",
                response.status(), rulesetVersion == null ? "-" : rulesetVersion,
                response.violations().stream().map(ValidationResponse.Violation::ruleId).toList(),
                fields(response.errors()), response.warnings().stream().map(AppliedDefault::field).toList());
    }

    /** Logs a batch once it is done (each item was counted with {@link #record}). */
    void batch(List<BatchResponse.Item> results, String rulesetVersion, long millis) {
        registry.summary("fafsa.batch.size").record(results.size());
        log.info("Validated batch: size={} ruleset={} statuses={} durationMs={}", results.size(), rulesetVersion,
                results.stream().collect(Collectors.groupingBy(i -> i.result().status(), Collectors.counting())),
                millis);
    }

    void batchRejected(String reason) {
        registry.counter("fafsa.batch.rejected").increment();
        log.info("Rejected batch: {}", reason);
    }

    void malformedBody(Exception cause) {
        registry.counter("fafsa.validations", "mode", SINGLE, "status", ValidationResponse.INVALID_INPUT).increment();
        // Only the exception type: parser messages can quote the request body
        log.info("Rejected malformed request body ({})", cause.getClass().getSimpleName());
    }

    /** Field paths for the log; unknown field names come from the caller, so they are not logged. */
    private static List<String> fields(List<InputError> errors) {
        return errors.stream()
                .map(e -> ApplicationData.isKnownPath(e.field()) ? e.field() : "unknown field")
                .toList();
    }
}
