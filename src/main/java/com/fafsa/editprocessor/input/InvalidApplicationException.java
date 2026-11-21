package com.fafsa.editprocessor.input;

import java.util.List;

/**
 * The application input has problems. Carries all of them, not just the first, and the defaults
 * that would be applied to the fields that have no problem.
 */
public class InvalidApplicationException extends RuntimeException {

    private final List<InputError> errors;
    private final List<AppliedDefault> warnings;

    public InvalidApplicationException(List<InputError> errors, List<AppliedDefault> warnings) {
        super(errors.size() + (errors.size() == 1 ? " problem" : " problems") + " with the application");
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    public List<InputError> getErrors() {
        return errors;
    }

    public List<AppliedDefault> getWarnings() {
        return warnings;
    }
}
