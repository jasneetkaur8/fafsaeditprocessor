package com.fafsa.editprocessor.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.compiler.RuleSetCompiler;
import com.fafsa.editprocessor.input.DependencyStatus;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fafsa.editprocessor.input.FafsaApplication.StudentInfo;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    private final RuleSetCompiler compiler = new RuleSetCompiler(CLOCK);
    private final RuleEngine engine = new RuleEngine(CLOCK);

    private static final FafsaApplication APPLICATION = new FafsaApplication(
            new StudentInfo("Jane", "Smith", "123456789", LocalDate.of(2003, 5, 15)),
            DependencyStatus.INDEPENDENT, "single", null, new Household(3, 1),
            new Income(new BigDecimal("12.50"), null), "CA");

    @Test
    void onlyWarningFailuresGiveValidWithWarnings() {
        ValidationResult result = engine.evaluate(ruleSet("""
                  - id: LOW_INCOME
                    severity: WARNING
                    expect: income.studentIncome >= 100
                    message: "Student income ${income.studentIncome} looks low"
                    tests: [{ input: { income: { studentIncome: 500 } }, expect: PASSED }]
                  - id: NOTE
                    severity: INFO
                    expect: "false"
                    message: note
                    tests: [{ input: {}, expect: FAILED }]
                """), APPLICATION);

        assertThat(result.status()).isEqualTo(ApplicationStatus.VALID_WITH_WARNINGS);
        assertThat(result.violations()).extracting(RuleResult::message)
                .containsExactly("Student income 12.5 looks low", "note");
    }

    @Test
    void onlyInfoFailuresStayValid() {
        ValidationResult result = engine.evaluate(ruleSet("""
                  - id: NOTE
                    severity: INFO
                    expect: "false"
                    message: note
                    tests: [{ input: {}, expect: FAILED }]
                """), APPLICATION);

        assertThat(result.status()).isEqualTo(ApplicationStatus.VALID);
        assertThat(result.violations()).hasSize(1);
    }

    @Test
    void ruleThatCannotBeEvaluatedMakesTheApplicationInvalidWithoutStoppingOthers() {
        // Comparing a text field with a number has no meaning, so the first rule errors at runtime
        ValidationResult result = engine.evaluate(ruleSet("""
                  - id: BROKEN
                    severity: WARNING
                    expect: stateOfResidence > 5
                    message: m
                    tests: [{ input: { stateOfResidence: CA }, expect: ERRORED }]
                  - id: FINE
                    severity: ERROR
                    expect: stateOfResidence == "CA"
                    message: m
                    tests: [{ input: { stateOfResidence: CA }, expect: PASSED }]
                """), APPLICATION);

        assertThat(result.status()).isEqualTo(ApplicationStatus.INVALID);
        assertThat(result.results()).extracting(RuleResult::outcome)
                .containsExactly(RuleOutcome.ERRORED, RuleOutcome.PASSED);
        assertThat(result.results().get(0).message())
                .isEqualTo("This rule could not be evaluated for the application");
    }

    private CompiledRuleSet ruleSet(String rules) {
        return compiler.compile("rulesetVersion: test\nrules:\n" + rules);
    }
}
