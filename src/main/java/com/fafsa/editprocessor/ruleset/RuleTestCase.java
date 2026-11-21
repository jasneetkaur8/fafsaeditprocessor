package com.fafsa.editprocessor.ruleset;

import java.util.Map;

/**
 * An example embedded in a rule: a (partial) application and the outcome the rule must give it.
 * Every test runs when the rule set is loaded; a rule set with a failing test is rejected.
 *
 * @param name   optional label shown in error messages
 * @param input  application fields, in the same shape as the request JSON
 * @param expect the outcome the rule must produce
 */
public record RuleTestCase(String name, Map<String, Object> input, RuleOutcome expect) {
}
