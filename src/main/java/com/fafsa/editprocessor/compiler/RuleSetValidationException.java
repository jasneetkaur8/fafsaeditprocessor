package com.fafsa.editprocessor.compiler;

import java.util.List;

/** A rule set was rejected. Carries every problem found, not just the first. */
public class RuleSetValidationException extends RuntimeException {

    private final List<String> errors;

    public RuleSetValidationException(List<String> errors) {
        super("Rule set rejected:\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    /** Each problem, prefixed with the rule id it belongs to where there is one. */
    public List<String> getErrors() {
        return errors;
    }
}
