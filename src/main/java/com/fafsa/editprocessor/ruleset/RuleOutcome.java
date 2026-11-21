package com.fafsa.editprocessor.ruleset;

/** Result of running one rule against one application. */
public enum RuleOutcome {
    /** {@code expect} was true. */
    PASSED,
    /** {@code expect} was false. */
    FAILED,
    /** A {@code requires} field was missing, or {@code when} was false, so the rule did not apply. */
    NOT_APPLICABLE,
    /** The rule could not be evaluated (for example, a value of an unexpected type). */
    ERRORED
}
