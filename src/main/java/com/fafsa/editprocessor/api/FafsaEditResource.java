package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.engine.RuleEngine;
import com.fafsa.editprocessor.engine.ValidationResult;
import com.fafsa.editprocessor.input.ApplicationValidator;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.input.InvalidApplicationException;
import com.fafsa.editprocessor.input.ValidatedApplication;
import com.fafsa.editprocessor.store.RuleSetStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Validates FAFSA applications: one at a time ({@code /validate}) or as an array ({@code /validate/batch}).
 *
 * <p>For a single application the body arrives as a checked {@link FafsaApplication}:
 * {@code FafsaApplicationMessageConverter} deserializes it and reports every bad value and missing
 * required field together in one 400. {@link ApplicationValidator} then fills in defaults, which
 * are listed in the response, and the completed application runs through the active rule set;
 * failed rules are reported in the response (still a 200, since the application was processed).
 */
@RestController
@RequestMapping("/api/v1/applications")
public class FafsaEditResource {

    private final ApplicationValidator applicationValidator;
    private final RuleSetStore ruleSetStore;
    private final RuleEngine ruleEngine;
    private final ObjectMapper mapper;
    private final ValidationTelemetry telemetry;
    private final int maxBatchSize;

    public FafsaEditResource(ApplicationValidator applicationValidator, RuleSetStore ruleSetStore,
            RuleEngine ruleEngine, ObjectMapper mapper, ValidationTelemetry telemetry,
            @Value("${fafsa.validation.max-batch-size}") int maxBatchSize) {
        this.applicationValidator = applicationValidator;
        this.ruleSetStore = ruleSetStore;
        this.ruleEngine = ruleEngine;
        this.mapper = mapper;
        this.telemetry = telemetry;
        this.maxBatchSize = maxBatchSize;
    }

    @PostMapping(
            path = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(@RequestBody FafsaApplication application) {
        CompiledRuleSet rules = ruleSetStore.active(); // one rule-set snapshot per request
        ValidationResponse response = evaluate(application, rules);
        telemetry.single(response, rules.version());
        return response;
    }

    /**
     * Validates a JSON array of applications. Each one is checked independently, exactly as by
     * {@link #validate}, and gets its own result with its {@code index} in the array: an invalid
     * application does not fail the batch. The response is a 400 only when the body itself is not
     * a usable array (broken JSON, not an array, empty, or more than {@code fafsa.validation.max-batch-size}).
     */
    @PostMapping(
            path = "/validate/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateBatch(@RequestBody String body) {
        JsonNode applications;
        try {
            applications = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            applications = null;
        }
        if (applications == null || !applications.isArray()) {
            return badBatch("Request body must be a valid JSON array of applications");
        }
        if (applications.isEmpty()) {
            return badBatch("Batch must contain at least one application");
        }
        if (applications.size() > maxBatchSize) {
            return badBatch("Batch must contain at most " + maxBatchSize + " applications");
        }

        long start = System.nanoTime();
        CompiledRuleSet rules = ruleSetStore.active(); // the same rules for every item in the batch
        List<BatchResponse.Item> results = new ArrayList<>();
        for (int i = 0; i < applications.size(); i++) {
            ValidationResponse result = validateItem(applications.get(i), rules);
            telemetry.record(ValidationTelemetry.BATCH, result);
            results.add(new BatchResponse.Item(i, result));
        }
        telemetry.batch(results, rules.version(), (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.ok(new BatchResponse(results));
    }

    private ValidationResponse validateItem(JsonNode item, CompiledRuleSet rules) {
        if (!(item instanceof ObjectNode application)) {
            return ValidationResponse.invalidInput("Application must be a JSON object", List.of(), List.of());
        }
        try {
            return evaluate(applicationValidator.toApplication(application), rules);
        } catch (InvalidApplicationException e) {
            return ValidationResponse.invalidInput(e.getMessage(), e.getErrors(), e.getWarnings());
        }
    }

    private ValidationResponse evaluate(FafsaApplication application, CompiledRuleSet rules) {
        ValidatedApplication validated = applicationValidator.applyDefaults(application);
        ValidationResult result = ruleEngine.evaluate(rules, validated.application());
        return ValidationResponse.from(result, validated.defaultsApplied());
    }

    private ResponseEntity<ValidationResponse> badBatch(String message) {
        telemetry.batchRejected(message);
        return ResponseEntity.badRequest().body(ValidationResponse.invalidInput(message, List.of(), List.of()));
    }
}
