package com.fafsa.editprocessor.compiler;

import com.fafsa.editprocessor.input.ApplicationData;
import com.google.protobuf.NullValue;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A validated rule set ready to run. Immutable and thread-safe. */
public final class CompiledRuleSet {

    /** CEL variable holding the rule set's named lists. */
    public static final String LISTS_VARIABLE = "lists";
    /** CEL variable holding today's date as {@code "yyyy-MM-dd"}. */
    public static final String TODAY_VARIABLE = "today";

    private final String version;
    private final List<CompiledRule> rules;
    private final Map<String, List<Object>> lists;

    CompiledRuleSet(String version, List<CompiledRule> rules, Map<String, List<Object>> lists) {
        this.version = version;
        this.rules = List.copyOf(rules);
        this.lists = Map.copyOf(lists);
    }

    public String version() {
        return version;
    }

    /** Rules in the order they appear in the file. */
    public List<CompiledRule> rules() {
        return rules;
    }

    /**
     * The CEL variables for one application: every top-level application field, plus
     * {@value #LISTS_VARIABLE} and {@value #TODAY_VARIABLE}.
     *
     * <p>A field that was not provided is still bound, so a rule never fails just for mentioning it:
     * an object field (such as {@code spouseInfo}) as an empty map, so {@code has(spouseInfo.ssn)} is
     * false, and any other field as {@code null}.
     */
    public Map<String, Object> activation(ApplicationData data, LocalDate today) {
        Map<String, Object> variables = new HashMap<>();
        for (String name : ApplicationData.topLevelFields()) {
            Object value = data.values().get(name);
            if (value == null) {
                value = ApplicationData.isObjectField(name) ? Map.of() : NullValue.NULL_VALUE;
            }
            variables.put(name, value);
        }
        variables.put(LISTS_VARIABLE, lists);
        variables.put(TODAY_VARIABLE, today.toString());
        return variables;
    }
}
