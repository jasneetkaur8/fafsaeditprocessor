package com.fafsa.editprocessor.engine;

import com.fafsa.editprocessor.ruleset.RuleOutcome;
import java.util.List;

/**
 * Result of running a whole rule set against one application: every rule's result, in rule order.
 */
public record ValidationResult(String rulesetVersion, ApplicationStatus status, List<RuleResult> results) {

    public ValidationResult {
        results = List.copyOf(results);
    }

    public List<RuleResult> violations() {
        return results.stream().filter(RuleResult::isViolation).toList();
    }

    public int count(RuleOutcome outcome) {
        return (int) results.stream().filter(r -> r.outcome() == outcome).count();
    }
}
