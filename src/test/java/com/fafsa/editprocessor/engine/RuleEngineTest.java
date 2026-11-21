package com.fafsa.editprocessor.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.compiler.RuleSetCompiler;
import com.fafsa.editprocessor.input.DependencyStatus;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fafsa.editprocessor.input.FafsaApplication.StudentInfo;
import com.fafsa.editprocessor.input.MaritalStatus;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class RuleEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    private final RuleSetCompiler compiler = new RuleSetCompiler(CLOCK);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RuleEngine engine = new RuleEngine(CLOCK, registry, 0, 0);

    private static final FafsaApplication APPLICATION = new FafsaApplication(
            new StudentInfo("Jane", "Smith", "123456789", LocalDate.of(2003, 5, 15)),
            DependencyStatus.INDEPENDENT, MaritalStatus.SINGLE, null, new Household(3, 1),
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

    @Test
    void ruleOutcomesAndEvaluationTimeAreRecorded() {
        CompiledRuleSet rules = ruleSet("""
                  - id: CA_ONLY
                    severity: ERROR
                    expect: stateOfResidence == "CA"
                    message: m
                    tests: [{ input: { stateOfResidence: CA }, expect: PASSED }]
                  - id: NOTE
                    severity: INFO
                    expect: "false"
                    message: note
                    tests: [{ input: {}, expect: FAILED }]
                """);

        engine.evaluate(rules, APPLICATION);
        engine.evaluate(rules, APPLICATION);

        assertThat(registry.counter("fafsa.rule.results", "rule", "CA_ONLY", "outcome", "PASSED").count()).isEqualTo(2);
        assertThat(registry.counter("fafsa.rule.results", "rule", "NOTE", "outcome", "FAILED").count()).isEqualTo(2);
        assertThat(registry.timer("fafsa.rules.evaluation").count()).isEqualTo(2);
    }

    /** 12 rules: some pass, some fail with a rendered message, one errors, one does not apply. */
    private static final String MIXED_RULES = """
              - id: PASS_1
                severity: ERROR
                expect: stateOfResidence == "CA"
                message: m
                tests: [{ input: { stateOfResidence: CA }, expect: PASSED }]
              - id: FAIL_WARNING
                severity: WARNING
                expect: income.studentIncome >= 100
                message: "Student income ${income.studentIncome} looks low"
                tests: [{ input: { income: { studentIncome: 500 } }, expect: PASSED }]
              - id: BROKEN
                severity: INFO
                expect: stateOfResidence > 5
                message: m
                tests: [{ input: { stateOfResidence: CA }, expect: ERRORED }]
              - id: NOT_MARRIED
                severity: ERROR
                when: maritalStatus == "married"
                expect: "false"
                message: m
                tests: [{ input: { maritalStatus: single }, expect: NOT_APPLICABLE }]
            """ + IntStream.rangeClosed(2, 9).mapToObj(i -> """
              - id: PASS_%d
                severity: ERROR
                expect: household.numberInHousehold >= %d
                message: m
                tests: [{ input: { household: { numberInHousehold: 3 } }, expect: PASSED }]
            """.formatted(i, i % 4)).collect(Collectors.joining());

    @Test
    void parallelEvaluationGivesTheSameResultsInRuleOrder() {
        CompiledRuleSet rules = ruleSet(MIXED_RULES);
        RuleEngine parallel = new RuleEngine(CLOCK, new SimpleMeterRegistry(), 1, 4);
        try {
            ValidationResult sequentialResult = engine.evaluate(rules, APPLICATION);
            for (int i = 0; i < 50; i++) {
                assertThat(parallel.evaluate(rules, APPLICATION)).isEqualTo(sequentialResult);
            }
            assertThat(sequentialResult.results()).extracting(RuleResult::ruleId).startsWith(
                    "PASS_1", "FAIL_WARNING", "BROKEN", "NOT_MARRIED", "PASS_2");
            assertThat(sequentialResult.results()).extracting(RuleResult::outcome).startsWith(
                    RuleOutcome.PASSED, RuleOutcome.FAILED, RuleOutcome.ERRORED, RuleOutcome.NOT_APPLICABLE);
            assertThat(sequentialResult.status()).isEqualTo(ApplicationStatus.INVALID);
        } finally {
            parallel.shutdown();
        }
    }

    @Test
    void rulesRunOnThePoolOnlyFromTheThresholdAndKeepTheRequestId() {
        CompiledRuleSet rules = ruleSet(MIXED_RULES); // 12 rules; BROKEN logs a warning
        RuleEngine atThreshold = new RuleEngine(CLOCK, new SimpleMeterRegistry(), 12, 2);
        RuleEngine aboveRuleCount = new RuleEngine(CLOCK, new SimpleMeterRegistry(), 13, 2);
        Logger logger = (Logger) LoggerFactory.getLogger(RuleEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("requestId", "req-7");
        try {
            atThreshold.evaluate(rules, APPLICATION);
            aboveRuleCount.evaluate(rules, APPLICATION);

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getThreadName()).startsWith("rule-eval-");
            assertThat(appender.list.get(1).getThreadName()).isEqualTo(Thread.currentThread().getName());
            assertThat(appender.list).allSatisfy(event ->
                    assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "req-7"));
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
            atThreshold.shutdown();
            aboveRuleCount.shutdown();
        }
    }

    @Test
    void negativeSettingsAreRejected() {
        assertThatThrownBy(() -> new RuleEngine(CLOCK, registry, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CompiledRuleSet ruleSet(String rules) {
        return compiler.compile("rulesetVersion: test\nrules:\n" + rules);
    }
}
