package com.fafsa.editprocessor.ruleset;

import java.util.List;

/**
 * One edit rule, exactly as written in the YAML file.
 *
 * @param id          unique rule id, e.g. {@code SSN_FORMAT}
 * @param severity    how serious a failure is
 * @param description what the rule checks, in plain language
 * @param requires    field paths that must be present; if any is missing the rule is NOT_APPLICABLE
 * @param when        optional CEL condition; if false the rule is NOT_APPLICABLE
 * @param expect      CEL condition that must be true for the application to pass
 * @param field       optional path of the field reported as wrong
 * @param message     message shown on failure; {@code ${field.path}} is replaced with that field's value
 * @param remediation optional advice on how to fix a failure
 * @param tests       examples that must produce their expected outcome
 */
public record RuleDefinition(
        String id,
        Severity severity,
        String description,
        List<String> requires,
        String when,
        String expect,
        String field,
        String message,
        String remediation,
        List<RuleTestCase> tests) {
}
