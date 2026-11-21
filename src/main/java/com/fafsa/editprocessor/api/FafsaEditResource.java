package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.ApplicationValidator;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fafsa.editprocessor.input.ValidatedApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingests a FAFSA application as JSON and returns its validation result.
 *
 * <p>The application is first checked by {@link ApplicationValidator}: missing required
 * information is a 400, and any defaults it fills in are listed in the response. Until the rule
 * engine exists (Step 6), every application that passes that check is reported as valid.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class FafsaEditResource {

    private final ApplicationValidator applicationValidator;

    public FafsaEditResource(ApplicationValidator applicationValidator) {
        this.applicationValidator = applicationValidator;
    }

    @PostMapping(
            path = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationResponse validate(@RequestBody FafsaApplication application) {
        ValidatedApplication validated = applicationValidator.validate(application);
        return ValidationResponse.valid(validated.defaultsApplied());
    }
}
