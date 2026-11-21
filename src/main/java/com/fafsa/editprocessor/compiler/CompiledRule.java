package com.fafsa.editprocessor.compiler;

import com.fafsa.editprocessor.input.ApplicationData;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import com.fafsa.editprocessor.ruleset.Severity;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import java.util.List;
import java.util.Map;

/**
 * A rule ready to run. Immutable and thread-safe: its CEL programs are built once, when the rule
 * set is compiled, and shared by every request.
 */
public final class CompiledRule {

    /** Outcome of one evaluation; {@code error} explains an ERRORED outcome and is otherwise null. */
    public record Evaluation(RuleOutcome outcome, String error) {
    }

    private final String id;
    private final Severity severity;
    private final String description;
    private final List<String> requires;
    private final CelRuntime.Program when;
    private final CelRuntime.Program expect;
    private final String field;
    private final String message;
    private final String remediation;

    CompiledRule(String id, Severity severity, String description, List<String> requires,
            CelRuntime.Program when, CelRuntime.Program expect, String field, String message,
            String remediation) {
        this.id = id;
        this.severity = severity;
        this.description = description;
        this.requires = List.copyOf(requires);
        this.when = when;
        this.expect = expect;
        this.field = field;
        this.message = message;
        this.remediation = remediation;
    }

    /**
     * {@code requires} (a field missing: NOT_APPLICABLE), then {@code when} (false: NOT_APPLICABLE),
     * then {@code expect} (true: PASSED, false: FAILED). Never throws: a problem is ERRORED.
     *
     * @param data       the application, used for {@code requires}
     * @param activation the CEL variables, from {@link CompiledRuleSet#activation}
     */
    public Evaluation evaluate(ApplicationData data, Map<String, Object> activation) {
        for (String path : requires) {
            if (data.lookup(path).isEmpty()) {
                return new Evaluation(RuleOutcome.NOT_APPLICABLE, null);
            }
        }
        try {
            if (when != null) {
                Object applies = when.eval(activation);
                if (!(applies instanceof Boolean)) {
                    return errored("'when' returned " + describe(applies) + " instead of true or false");
                }
                if (!(Boolean) applies) {
                    return new Evaluation(RuleOutcome.NOT_APPLICABLE, null);
                }
            }
            Object passed = expect.eval(activation);
            if (!(passed instanceof Boolean)) {
                return errored("'expect' returned " + describe(passed) + " instead of true or false");
            }
            return new Evaluation((Boolean) passed ? RuleOutcome.PASSED : RuleOutcome.FAILED, null);
        } catch (CelEvaluationException | RuntimeException e) {
            return errored(e.getMessage());
        }
    }

    private static Evaluation errored(String error) {
        return new Evaluation(RuleOutcome.ERRORED, error);
    }

    private static String describe(Object value) {
        return value == null ? "nothing" : "a " + value.getClass().getSimpleName();
    }

    public String id() {
        return id;
    }

    public Severity severity() {
        return severity;
    }

    public String description() {
        return description;
    }

    public String field() {
        return field;
    }

    public String message() {
        return message;
    }

    public String remediation() {
        return remediation;
    }
}
