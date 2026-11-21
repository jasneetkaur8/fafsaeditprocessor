package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.engine.ApplicationStatus;
import com.fafsa.editprocessor.engine.RuleResult;
import com.fafsa.editprocessor.engine.ValidationResult;
import com.fafsa.editprocessor.input.AppliedDefault;
import com.fafsa.editprocessor.input.InputError;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Body of every validation response. Lists that would be empty are left out.
 *
 * @param status     {@code INVALID_INPUT} (400: the input checks failed, rules did not run), or
 *                   the rules' result: VALID, VALID_WITH_WARNINGS or INVALID
 * @param message    human-readable summary
 * @param errors     input problems, only for {@code INVALID_INPUT}
 * @param warnings   defaults filled in (or, for {@code INVALID_INPUT}, that would be filled in) for
 *                   information the application left out
 * @param violations rules that failed or could not be evaluated, in rule order
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ValidationResponse(
        String status,
        String message,
        List<InputError> errors,
        List<AppliedDefault> warnings,
        List<Violation> violations) {

    static final String INVALID_INPUT = "INVALID_INPUT";

    /** One rule that the application did not pass. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Violation(
            String ruleId,
            String severity,
            RuleOutcome outcome,
            String field,
            String message,
            String remediation) {

        static Violation of(RuleResult result) {
            return new Violation(result.ruleId(), result.severity().name(), result.outcome(),
                    result.field(), result.message(), result.remediation());
        }
    }

    /** The input checks failed, so the rules did not run. */
    public static ValidationResponse invalidInput(String message, List<InputError> errors,
            List<AppliedDefault> warnings) {
        return new ValidationResponse(INVALID_INPUT, message, List.copyOf(errors), List.copyOf(warnings), List.of());
    }

    /**
     * The rules ran. A defaulted value is a warning, so an application that passes every rule but
     * needed defaults is VALID_WITH_WARNINGS.
     */
    public static ValidationResponse from(ValidationResult result, List<AppliedDefault> defaultsApplied) {
        List<Violation> violations = result.violations().stream().map(Violation::of).toList();
        ApplicationStatus status = result.status();
        if (status == ApplicationStatus.VALID && !defaultsApplied.isEmpty()) {
            status = ApplicationStatus.VALID_WITH_WARNINGS;
        }
        return new ValidationResponse(status.name(), messageFor(status, violations.size(), defaultsApplied.size()),
                List.of(), List.copyOf(defaultsApplied), violations);
    }

    private static String messageFor(ApplicationStatus status, int violations, int defaults) {
        switch (status) {
            case VALID:
                return "Valid application";
            case VALID_WITH_WARNINGS:
                // Here every violation is a WARNING or INFO rule, so it counts as a warning too
                int warnings = defaults + violations;
                return "Valid application with " + warnings + (warnings == 1 ? " warning" : " warnings");
            default:
                return "Application failed " + violations + (violations == 1 ? " rule" : " rules");
        }
    }
}
