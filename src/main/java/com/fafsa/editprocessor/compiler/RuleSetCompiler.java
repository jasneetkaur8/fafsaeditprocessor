package com.fafsa.editprocessor.compiler;

import com.fafsa.editprocessor.functions.RuleFunctions;
import com.fafsa.editprocessor.input.ApplicationData;
import com.fafsa.editprocessor.ruleset.RuleDefinition;
import com.fafsa.editprocessor.ruleset.RuleSetDefinition;
import com.fafsa.editprocessor.ruleset.RuleSetParser;
import com.fafsa.editprocessor.ruleset.RuleTestCase;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a rule file into a ready-to-run {@link CompiledRuleSet}, or rejects it with every problem
 * listed. A rule set that fails here never goes live.
 *
 * <p>Checks: a version and at least one rule; unique, non-blank rule ids; a severity, an
 * {@code expect} and a message on every rule; {@code when} and {@code expect} compile and give
 * true/false; every field path (in {@code requires}, {@code field} and message placeholders) is a
 * real application field; every {@code lists.<name>} used is defined; and every rule has tests,
 * all of which produce their expected outcome.
 */
@Component
public class RuleSetCompiler {

    private static final Pattern LIST_REFERENCE = Pattern.compile("\\blists\\.([A-Za-z_][A-Za-z0-9_]*)");

    private final Clock clock;
    private final CelCompiler celCompiler;
    private final CelRuntime celRuntime;

    public RuleSetCompiler(Clock clock) {
        this.clock = clock;
        // Lets money (a double) be compared with whole-number literals, e.g. income.parentIncome >= 0
        CelOptions options = CelOptions.current().enableHeterogeneousNumericComparisons(true).build();

        CelCompilerBuilder compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(options)
                // has(), all(), exists(), map(), filter()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addLibraries(CelExtensions.strings())
                .addFunctionDeclarations(RuleFunctions.declarations())
                .addVar(CompiledRuleSet.LISTS_VARIABLE, MapType.create(SimpleType.STRING, ListType.create(SimpleType.DYN)))
                .addVar(CompiledRuleSet.TODAY_VARIABLE, SimpleType.STRING);
        for (String field : ApplicationData.topLevelFields()) {
            compiler.addVar(field, SimpleType.DYN);
        }
        this.celCompiler = compiler.build();
        this.celRuntime = CelRuntimeFactory.standardCelRuntimeBuilder()
                .setOptions(options)
                .addLibraries(CelExtensions.strings())
                .addFunctionBindings(RuleFunctions.bindings(clock))
                .build();
    }

    /** Parses and compiles a YAML rule file. */
    public CompiledRuleSet compile(String yaml) {
        RuleSetDefinition definition;
        try {
            definition = RuleSetParser.parse(yaml);
        } catch (IllegalArgumentException e) {
            throw new RuleSetValidationException(List.of(e.getMessage()));
        }
        return compile(definition);
    }

    /** @throws RuleSetValidationException listing every problem */
    public CompiledRuleSet compile(RuleSetDefinition definition) {
        List<String> errors = new ArrayList<>();
        if (definition.rulesetVersion() == null || definition.rulesetVersion().isBlank()) {
            errors.add("rulesetVersion is required");
        }
        Map<String, List<Object>> lists = normalizeLists(definition.lists(), errors);
        List<RuleDefinition> ruleDefinitions = definition.rules() == null ? List.of() : definition.rules();
        if (ruleDefinitions.isEmpty()) {
            errors.add("rules: at least one rule is required");
        }

        List<CompiledRule> compiled = new ArrayList<>();
        Map<CompiledRule, List<RuleTestCase>> testsByRule = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < ruleDefinitions.size(); i++) {
            RuleDefinition rule = ruleDefinitions.get(i);
            if (rule == null) {
                errors.add("rule #" + (i + 1) + " is empty");
                continue;
            }
            CompiledRule result = compileRule(rule, i, lists.keySet(), seenIds, errors);
            if (result != null) {
                compiled.add(result);
                testsByRule.put(result, rule.tests());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuleSetValidationException(errors);
        }

        CompiledRuleSet ruleSet = new CompiledRuleSet(definition.rulesetVersion(), compiled, lists);
        testsByRule.forEach((rule, tests) -> runTests(ruleSet, rule, tests, errors));
        if (!errors.isEmpty()) {
            throw new RuleSetValidationException(errors);
        }
        return ruleSet;
    }

    private CompiledRule compileRule(RuleDefinition rule, int index, Set<String> listNames,
            Set<String> seenIds, List<String> errors) {
        int errorsBefore = errors.size();
        String id = rule.id();
        if (id == null || id.isBlank()) {
            id = "rule #" + (index + 1);
            errors.add(id + ": id is required");
        } else if (!seenIds.add(id)) {
            errors.add(id + ": duplicate rule id");
        }
        if (rule.severity() == null) {
            errors.add(id + ": severity is required (ERROR, WARNING or INFO)");
        }
        if (rule.message() == null || rule.message().isBlank()) {
            errors.add(id + ": message is required");
        } else {
            for (String path : MessageTemplate.placeholders(rule.message())) {
                checkPath(id, "message placeholder ${" + path + "}", path, errors);
            }
        }
        List<String> requires = rule.requires() == null ? List.of() : rule.requires();
        for (String path : requires) {
            checkPath(id, "requires", path, errors);
        }
        if (rule.field() != null) {
            checkPath(id, "field", rule.field(), errors);
        }
        if (rule.tests() == null || rule.tests().isEmpty()) {
            errors.add(id + ": at least one test is required");
        }

        CelRuntime.Program when = null;
        if (rule.when() != null) {
            when = compileCondition(id, "when", rule.when(), listNames, errors);
        }
        CelRuntime.Program expect = null;
        if (rule.expect() == null || rule.expect().isBlank()) {
            errors.add(id + ": expect is required");
        } else {
            expect = compileCondition(id, "expect", rule.expect(), listNames, errors);
        }

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new CompiledRule(id, rule.severity(), rule.description(), requires, when, expect,
                rule.field(), rule.message(), rule.remediation());
    }

    private CelRuntime.Program compileCondition(String id, String label, String expression,
            Set<String> listNames, List<String> errors) {
        if (expression.isBlank()) {
            errors.add(id + ": " + label + " is blank");
            return null;
        }
        Matcher lists = LIST_REFERENCE.matcher(expression);
        boolean listsOk = true;
        while (lists.find()) {
            if (!listNames.contains(lists.group(1))) {
                errors.add(id + ": " + label + " uses lists." + lists.group(1) + ", which is not defined under 'lists'");
                listsOk = false;
            }
        }
        try {
            CelAbstractSyntaxTree ast = celCompiler.compile(expression).getAst();
            CelType type = ast.getResultType();
            if (!type.equals(SimpleType.BOOL) && !type.equals(SimpleType.DYN)) {
                errors.add(id + ": " + label + " must give true or false, but gives " + type.name());
                return null;
            }
            return listsOk ? celRuntime.createProgram(ast) : null;
        } catch (CelValidationException e) {
            errors.add(id + ": " + label + " is not a valid expression: " + e.getMessage());
        } catch (CelEvaluationException e) {
            errors.add(id + ": " + label + " cannot be prepared: " + e.getMessage());
        }
        return null;
    }

    private void runTests(CompiledRuleSet ruleSet, CompiledRule rule, List<RuleTestCase> tests, List<String> errors) {
        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < tests.size(); i++) {
            RuleTestCase test = tests.get(i);
            String label = rule.id() + ": test " + (i + 1)
                    + (test != null && test.name() != null ? " (" + test.name() + ")" : "");
            if (test == null || test.expect() == null) {
                errors.add(label + " needs an expected outcome (PASSED, FAILED, NOT_APPLICABLE or ERRORED)");
                continue;
            }
            ApplicationData data;
            try {
                data = ApplicationData.fromMap(test.input());
            } catch (IllegalArgumentException e) {
                errors.add(label + " has invalid input: " + e.getMessage());
                continue;
            }
            CompiledRule.Evaluation actual = rule.evaluate(data, ruleSet.activation(data, today));
            if (actual.outcome() != test.expect()) {
                errors.add(label + " expected " + test.expect() + " but got " + actual.outcome()
                        + (actual.error() != null ? ": " + actual.error() : ""));
            }
        }
    }

    private static void checkPath(String id, String where, String path, List<String> errors) {
        if (!ApplicationData.isKnownPath(path)) {
            errors.add(id + ": " + where + " refers to unknown field '" + path + "'");
        }
    }

    /** YAML gives Integer; CEL needs Long. */
    private static Map<String, List<Object>> normalizeLists(Map<String, List<Object>> lists, List<String> errors) {
        Map<String, List<Object>> normalized = new LinkedHashMap<>();
        if (lists == null) {
            return normalized;
        }
        lists.forEach((name, values) -> {
            if (values == null) {
                errors.add("lists." + name + " is empty");
                return;
            }
            List<Object> converted = new ArrayList<>();
            for (Object value : values) {
                if (value == null) {
                    errors.add("lists." + name + " has a blank entry");
                    return;
                }
                converted.add(value instanceof Integer n ? Long.valueOf(n) : value);
            }
            normalized.put(name, List.copyOf(converted));
        });
        return normalized;
    }
}
