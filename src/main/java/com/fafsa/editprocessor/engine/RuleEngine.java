package com.fafsa.editprocessor.engine;

import com.fafsa.editprocessor.compiler.CompiledRule;
import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.compiler.MessageTemplate;
import com.fafsa.editprocessor.input.ApplicationData;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.ruleset.RuleOutcome;
import com.fafsa.editprocessor.ruleset.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every rule of a {@link CompiledRuleSet} against one application and collects all results;
 * one failing rule never stops the others.
 *
 * <p>Metrics: {@code fafsa.rule.results} (counter, tags {@code rule} and {@code outcome}) and
 * {@code fafsa.rules.evaluation} (timer: running all rules for one application).
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final Clock clock;
    private final MeterRegistry registry;
    private final Timer evaluationTimer;

    public RuleEngine(Clock clock, MeterRegistry registry) {
        this.clock = clock;
        this.registry = registry;
        this.evaluationTimer = Timer.builder("fafsa.rules.evaluation")
                .description("Time to run every rule against one application")
                .register(registry);
    }

    public ValidationResult evaluate(CompiledRuleSet ruleSet, FafsaApplication application) {
        return evaluationTimer.record(() -> run(ruleSet, application));
    }

    private ValidationResult run(CompiledRuleSet ruleSet, FafsaApplication application) {
        ApplicationData data = ApplicationData.from(application);
        Map<String, Object> activation = ruleSet.activation(data, LocalDate.now(clock));

        List<RuleResult> results = new ArrayList<>();
        for (CompiledRule rule : ruleSet.rules()) {
            CompiledRule.Evaluation evaluation = rule.evaluate(data, activation);
            String message = null;
            if (evaluation.outcome() == RuleOutcome.FAILED) {
                message = MessageTemplate.render(rule.message(), data);
            } else if (evaluation.outcome() == RuleOutcome.ERRORED) {
                // Log the cause for operators; the applicant only sees that the rule could not run
                log.warn("Rule {} (rule set {}) could not be evaluated: {}",
                        rule.id(), ruleSet.version(), evaluation.error());
                message = "This rule could not be evaluated for the application";
            }
            registry.counter("fafsa.rule.results", "rule", rule.id(), "outcome", evaluation.outcome().name())
                    .increment();
            results.add(new RuleResult(rule.id(), rule.severity(), evaluation.outcome(),
                    rule.field(), message, rule.remediation()));
        }
        return new ValidationResult(ruleSet.version(), statusOf(results), results);
    }

    private static ApplicationStatus statusOf(List<RuleResult> results) {
        boolean warning = false;
        for (RuleResult result : results) {
            if (result.outcome() == RuleOutcome.ERRORED
                    || (result.outcome() == RuleOutcome.FAILED && result.severity() == Severity.ERROR)) {
                return ApplicationStatus.INVALID;
            }
            if (result.outcome() == RuleOutcome.FAILED && result.severity() == Severity.WARNING) {
                warning = true;
            }
        }
        return warning ? ApplicationStatus.VALID_WITH_WARNINGS : ApplicationStatus.VALID;
    }
}
