package com.fafsa.editprocessor.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleSetCompilerTest {

    private final RuleSetCompiler compiler = new RuleSetCompiler(
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void productionRuleFileCompilesWithAllSevenRules() throws IOException {
        CompiledRuleSet ruleSet = compiler.compile(
                Files.readString(Path.of("src/main/resources/rules/fafsa-2026-27.yaml")));

        assertThat(ruleSet.version()).isEqualTo("2026-27.1");
        assertThat(ruleSet.rules()).extracting(CompiledRule::id).containsExactly(
                "STUDENT_AGE", "SSN_FORMAT", "DEPENDENT_PARENT_INCOME", "NON_NEGATIVE_INCOME",
                "HOUSEHOLD_COLLEGE_COUNT", "STATE_CODE", "MARRIED_SPOUSE_INFO");
    }

    @Test
    void ruleUsingAFieldThatWasNotProvidedStillEvaluates() {
        // maritalStatus is absent from the test input, so it is bound as null rather than erroring
        CompiledRuleSet ruleSet = compiler.compile(rule("""
                    when: maritalStatus == "married"
                    expect: has(spouseInfo.ssn)
                    tests:
                      - input: {}
                        expect: NOT_APPLICABLE
                      - input: { maritalStatus: married }
                        expect: FAILED
                """));

        assertThat(ruleSet.rules()).hasSize(1);
    }

    @Test
    void everyProblemIsReported() {
        String yaml = """
                rulesetVersion: ""
                rules:
                  - id: A
                    expect: household.numberInCollege
                    message: "Bad ${household.nope}"
                    requires: [income.salary]
                    field: spuseInfo
                    tests:
                      - input: {}
                        expect: PASSED
                  - id: A
                    severity: ERROR
                    expect: "stateOfResidence in lists.territories"
                    message: m
                  - id: C
                    severity: ERROR
                    expect: "1 +"
                    message: m
                    tests:
                      - input: {}
                        expect: PASSED
                  - id: D
                    severity: ERROR
                    expect: "'text'"
                    message: m
                    tests:
                      - input: {}
                        expect: PASSED
                """;

        assertThat(errorsOf(yaml)).satisfiesExactlyInAnyOrder(
                e -> assertThat(e).isEqualTo("rulesetVersion is required"),
                e -> assertThat(e).startsWith("A: severity is required"),
                e -> assertThat(e).isEqualTo("A: message placeholder ${household.nope} refers to unknown field 'household.nope'"),
                e -> assertThat(e).isEqualTo("A: requires refers to unknown field 'income.salary'"),
                e -> assertThat(e).isEqualTo("A: field refers to unknown field 'spuseInfo'"),
                e -> assertThat(e).isEqualTo("A: duplicate rule id"),
                e -> assertThat(e).isEqualTo("A: at least one test is required"),
                e -> assertThat(e).isEqualTo("A: expect uses lists.territories, which is not defined under 'lists'"),
                e -> assertThat(e).startsWith("C: expect is not a valid expression"),
                e -> assertThat(e).isEqualTo("D: expect must give true or false, but gives string"));
    }

    @Test
    void failingEmbeddedTestRejectsTheRuleSet() {
        assertThat(errorsOf(rule("""
                    expect: stateOfResidence == "CA"
                    tests:
                      - name: wrong expectation
                        input: { stateOfResidence: NY }
                        expect: PASSED
                """))).containsExactly("R: test 1 (wrong expectation) expected PASSED but got FAILED");
    }

    @Test
    void testInputMustMatchTheApplicationFormat() {
        assertThat(errorsOf(rule("""
                    expect: "true"
                    tests:
                      - input: { household: { size: 3 } }
                        expect: PASSED
                      - input: { studentInfo: { ssn: 123456789 } }
                        expect: PASSED
                """))).satisfiesExactly(
                        e -> assertThat(e).startsWith("R: test 1 has invalid input: Unrecognized field \"size\""),
                        e -> assertThat(e).startsWith("R: test 2 has invalid input: "));
    }

    @Test
    void unknownKeyInTheYamlIsRejected() {
        assertThat(errorsOf(rule("""
                    expct: "true"
                    tests: []
                """))).singleElement().asString()
                .startsWith("Rule set YAML is invalid at rules[0].expct: Unrecognized field \"expct\"");
    }

    @Test
    void yamlSyntaxErrorGivesTheLine() {
        assertThat(errorsOf("rulesetVersion: v1\nrules:\n  - id: \"unclosed\n"))
                .singleElement().asString()
                .startsWith("Rule set YAML is invalid")
                .contains("line 3, column 9");
    }

    @Test
    void wrongTypeInTheYamlGivesTheFieldPath() {
        assertThat(errorsOf("rulesetVersion: v1\nrules:\n  - id: [A, B]\n"))
                .singleElement().asString().startsWith("Rule set YAML is invalid at rules[0].id: ");
    }

    @Test
    void emptyRuleSetIsRejected() {
        assertThat(errorsOf("rulesetVersion: v1\nrules: []\n"))
                .containsExactly("rules: at least one rule is required");
    }

    /** A one-rule set with id R; {@code body} supplies the rest of the rule, indented 4 spaces. */
    private static String rule(String body) {
        return """
                rulesetVersion: test
                rules:
                  - id: R
                    severity: ERROR
                    message: m
                """ + body;
    }

    private List<String> errorsOf(String yaml) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> compiler.compile(yaml));
        assertThat(thrown).isInstanceOf(RuleSetValidationException.class);
        return ((RuleSetValidationException) thrown).getErrors();
    }

    @Test
    void nullYamlIsRejected() {
        assertThatThrownBy(() -> compiler.compile(" "))
                .isInstanceOf(RuleSetValidationException.class)
                .hasMessageContaining("Rule set is empty");
    }
}
