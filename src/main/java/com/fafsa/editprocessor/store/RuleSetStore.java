package com.fafsa.editprocessor.store;

import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.compiler.RuleSetCompiler;
import com.fafsa.editprocessor.compiler.RuleSetValidationException;
import com.fafsa.editprocessor.config.RulesProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Holds the active rule set.
 *
 * <p>Loads the rule file named by {@code fafsa.rules.location} at startup. If it is missing or
 * invalid, the application does not start, so it never runs with broken rules. The active set sits
 * behind an {@link AtomicReference} and each request takes one snapshot, ready for runtime
 * activation (Step 8) to swap in a new set without mixing old and new rules mid-request.
 */
@Component
public class RuleSetStore implements InfoContributor {

    private static final Logger log = LoggerFactory.getLogger(RuleSetStore.class);

    private final AtomicReference<CompiledRuleSet> active = new AtomicReference<>();
    private volatile Instant loadedAt;

    public RuleSetStore(RulesProperties properties, ResourceLoader resourceLoader, RuleSetCompiler compiler,
            MeterRegistry registry) {
        String location = properties.location();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("fafsa.rules.location is not set");
        }
        String yaml = read(resourceLoader.getResource(location), location);
        CompiledRuleSet ruleSet;
        try {
            ruleSet = compiler.compile(yaml);
        } catch (RuleSetValidationException e) {
            throw new IllegalStateException("Rule set at " + location + " is invalid. " + e.getMessage(), e);
        }
        active.set(ruleSet);
        loadedAt = Instant.now();
        Gauge.builder("fafsa.ruleset.rules", active, ref -> ref.get().rules().size())
                .description("Number of rules in the active rule set")
                .register(registry);
        log.info("Loaded rule set {} with {} rules from {}", ruleSet.version(), ruleSet.rules().size(), location);
    }

    /** Shows the active rule set under {@code ruleset} in {@code /actuator/info}. */
    @Override
    public void contribute(Info.Builder builder) {
        CompiledRuleSet ruleSet = active.get();
        builder.withDetail("ruleset", Map.of(
                "version", ruleSet.version(),
                "rules", ruleSet.rules().size(),
                "loadedAt", loadedAt.toString()));
    }

    /** The rule set to use for one request. Take it once per request. */
    public CompiledRuleSet active() {
        return active.get();
    }

    private static String read(Resource resource, String location) {
        if (!resource.exists()) {
            throw new IllegalStateException("Rule set not found at " + location);
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read rule set at " + location, e);
        }
    }
}
