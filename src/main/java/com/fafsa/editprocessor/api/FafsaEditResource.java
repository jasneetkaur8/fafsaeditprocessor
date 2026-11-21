package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.compiler.CompiledRuleSet;
import com.fafsa.editprocessor.engine.RuleEngine;
import com.fafsa.editprocessor.engine.ValidationResult;
import com.fafsa.editprocessor.input.ApplicationValidator;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.input.ValidatedApplication;
import com.fafsa.editprocessor.store.RuleSetStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingests a FAFSA application as JSON and returns its validation result.
 *
 * <p>The application is first checked by {@link ApplicationValidator}: missing required
 * information is a 400, and any defaults it fills in are listed in the response. The completed
 * application is then run through the active rule set; failed rules are reported in the response
 * (still a 200, since the application was processed).
 */
@RestController
@RequestMapping("/api/v1/applications")
public class FafsaEditResource {

    private final ApplicationValidator applicationValidator;
    private final RuleSetStore ruleSetStore;
    private final RuleEngine ruleEngine;

    public FafsaEditResource(ApplicationValidator applicationValidator, RuleSetStore ruleSetStore,
            RuleEngine ruleEngine) {
        this.applicationValidator = applicationValidator;
        this.ruleSetStore = ruleSetStore;
        this.ruleEngine = ruleEngine;
    }

    @PostMapping(
            path = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(@RequestBody FafsaApplication application) {
        ValidatedApplication validated = applicationValidator.validate(application);
        CompiledRuleSet rules = ruleSetStore.active(); // one snapshot for the whole request
        ValidationResult result = ruleEngine.evaluate(rules, validated.application());
        return ValidationResponse.from(result, validated.defaultsApplied());
    }
}
