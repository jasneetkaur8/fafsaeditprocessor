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
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs every rule of a {@link CompiledRuleSet} against one application and collects all results;
 * one failing rule never stops the others. Results are always in rule order.
 *
 * <p><b>Parallel evaluation.</b> When the rule set has at least {@code fafsa.engine.parallel.min-rules}
 * rules, the rules of one application run concurrently on a dedicated pool of
 * {@code fafsa.engine.parallel.threads} threads (0 = one per CPU core). This is safe because rules
 * only read the application: the compiled CEL programs, the application data and the CEL variables
 * are never modified during evaluation. Below the threshold, or with {@code min-rules: 0}, rules run
 * one after another on the request thread, which is faster when each rule takes microseconds.
 * The request's log context (request id) is carried onto the pool threads.
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
    private final int parallelMinRules;
    private final ExecutorService pool;

    /**
     * @param parallelMinRules run rules in parallel when a rule set has at least this many; 0 never
     * @param parallelThreads  size of the rule pool; 0 means one thread per CPU core
     */
    public RuleEngine(Clock clock, MeterRegistry registry,
            @Value("${fafsa.engine.parallel.min-rules:0}") int parallelMinRules,
            @Value("${fafsa.engine.parallel.threads:0}") int parallelThreads) {
        if (parallelMinRules < 0 || parallelThreads < 0) {
            throw new IllegalArgumentException("fafsa.engine.parallel.min-rules and threads must not be negative");
        }
        this.clock = clock;
        this.registry = registry;
        this.evaluationTimer = Timer.builder("fafsa.rules.evaluation")
                .description("Time to run every rule against one application")
                .register(registry);
        this.parallelMinRules = parallelMinRules;
        this.pool = parallelMinRules == 0 ? null : Executors.newFixedThreadPool(
                parallelThreads == 0 ? Runtime.getRuntime().availableProcessors() : parallelThreads,
                ruleThreads());
    }

    public ValidationResult evaluate(CompiledRuleSet ruleSet, FafsaApplication application) {
        return evaluationTimer.record(() -> run(ruleSet, application));
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    private ValidationResult run(CompiledRuleSet ruleSet, FafsaApplication application) {
        ApplicationData data = ApplicationData.from(application);
        Map<String, Object> activation = ruleSet.activation(data, LocalDate.now(clock));
        List<CompiledRule> rules = ruleSet.rules();

        List<RuleResult> results = pool != null && rules.size() >= parallelMinRules
                ? runInParallel(rules, ruleSet.version(), data, activation)
                : rules.stream().map(rule -> runRule(rule, ruleSet.version(), data, activation)).toList();
        return new ValidationResult(ruleSet.version(), statusOf(results), results);
    }

    /** One task per rule; invokeAll returns the futures in rule order, so results keep that order. */
    private List<RuleResult> runInParallel(List<CompiledRule> rules, String version, ApplicationData data,
            Map<String, Object> activation) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        List<Callable<RuleResult>> tasks = rules.stream()
                .<Callable<RuleResult>>map(rule -> () -> withLogContext(logContext,
                        () -> runRule(rule, version, data, activation)))
                .toList();
        try {
            List<Future<RuleResult>> futures = pool.invokeAll(tasks);
            List<RuleResult> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                results.add(resultOf(futures.get(i), rules.get(i), version));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while evaluating rules", e);
        }
    }

    private RuleResult resultOf(Future<RuleResult> future, CompiledRule rule, String version)
            throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            // runRule never throws; this is a safety net so one rule can never fail the others
            return errored(rule, version, String.valueOf(e.getCause()));
        }
    }

    private RuleResult runRule(CompiledRule rule, String version, ApplicationData data,
            Map<String, Object> activation) {
        CompiledRule.Evaluation evaluation = rule.evaluate(data, activation);
        if (evaluation.outcome() == RuleOutcome.ERRORED) {
            return errored(rule, version, evaluation.error());
        }
        String message = evaluation.outcome() == RuleOutcome.FAILED ? MessageTemplate.render(rule.message(), data) : null;
        count(rule, evaluation.outcome());
        return new RuleResult(rule.id(), rule.severity(), evaluation.outcome(), rule.field(), message,
                rule.remediation());
    }

    private RuleResult errored(CompiledRule rule, String version, String error) {
        // Log the cause for operators; the applicant only sees that the rule could not run
        log.warn("Rule {} (rule set {}) could not be evaluated: {}", rule.id(), version, error);
        count(rule, RuleOutcome.ERRORED);
        return new RuleResult(rule.id(), rule.severity(), RuleOutcome.ERRORED, rule.field(),
                "This rule could not be evaluated for the application", rule.remediation());
    }

    private void count(CompiledRule rule, RuleOutcome outcome) {
        registry.counter("fafsa.rule.results", "rule", rule.id(), "outcome", outcome.name()).increment();
    }

    private static <T> T withLogContext(Map<String, String> context, Supplier<T> work) {
        if (context != null) {
            MDC.setContextMap(context);
        }
        try {
            return work.get();
        } finally {
            MDC.clear();
        }
    }

    private static ThreadFactory ruleThreads() {
        AtomicInteger number = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "rule-eval-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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
