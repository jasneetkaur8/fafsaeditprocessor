package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.engine.ApplicationStatus;
import com.fafsa.editprocessor.engine.RuleResult;
import com.fafsa.editprocessor.engine.ValidationResult;
import com.fafsa.editprocessor.input.AppliedDefault;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import java.util.List;

/**
 * Result of validating one application.
 *
 * @param status          VALID, VALID_WITH_WARNINGS or INVALID
 * @param message         human-readable summary
 * @param rulesetVersion  version of the rule set that was applied
 * @param defaultsApplied values the application left out that were filled in with defaults;
 *                        empty when nothing was defaulted
 * @param violations      rules that failed or could not be evaluated, in rule order
 * @param summary         how many rules gave each outcome
 */
public record ValidationResponse(
        ApplicationStatus status,
        String message,
        String rulesetVersion,
        List<AppliedDefault> defaultsApplied,
        List<Violation> violations,
        Summary summary) {

    /** One rule that the application did not pass. */
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

    public record Summary(int rulesEvaluated, int passed, int failed, int notApplicable, int errored) {
    }

    public static ValidationResponse from(ValidationResult result, List<AppliedDefault> defaultsApplied) {
        List<Violation> violations = result.violations().stream().map(Violation::of).toList();
        Summary summary = new Summary(
                result.results().size(),
                result.count(RuleOutcome.PASSED),
                result.count(RuleOutcome.FAILED),
                result.count(RuleOutcome.NOT_APPLICABLE),
                result.count(RuleOutcome.ERRORED));
        return new ValidationResponse(result.status(), messageFor(result.status(), violations.size()),
                result.rulesetVersion(), List.copyOf(defaultsApplied), violations, summary);
    }

    private static String messageFor(ApplicationStatus status, int violations) {
        switch (status) {
            case VALID:
                return "Valid application";
            case VALID_WITH_WARNINGS:
                return "Valid application with " + violations + (violations == 1 ? " warning" : " warnings");
            default:
                return "Application failed " + violations + (violations == 1 ? " rule" : " rules");
        }
    }
}
