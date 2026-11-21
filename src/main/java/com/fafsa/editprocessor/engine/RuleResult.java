package com.fafsa.editprocessor.engine;

import com.fafsa.editprocessor.ruleset.RuleOutcome;
import com.fafsa.editprocessor.ruleset.Severity;

/**
 * What one rule decided about one application.
 *
 * @param message the rule's message with placeholders filled in when FAILED, an explanation when
 *                ERRORED, otherwise null
 */
public record RuleResult(
        String ruleId,
        Severity severity,
        RuleOutcome outcome,
        String field,
        String message,
        String remediation) {

    /** A result the applicant needs to know about: a failed rule, or one that could not run. */
    public boolean isViolation() {
        return outcome == RuleOutcome.FAILED || outcome == RuleOutcome.ERRORED;
    }
}
