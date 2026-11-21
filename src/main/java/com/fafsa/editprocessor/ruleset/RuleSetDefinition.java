package com.fafsa.editprocessor.ruleset;

import java.util.List;
import java.util.Map;

/**
 * A whole rule file, exactly as written in YAML.
 *
 * @param rulesetVersion version label reported with every validation result
 * @param lists          named value lists that rules can use as {@code lists.<name>}
 * @param rules          the rules, evaluated in this order
 */
public record RuleSetDefinition(
        String rulesetVersion,
        Map<String, List<Object>> lists,
        List<RuleDefinition> rules) {
}
