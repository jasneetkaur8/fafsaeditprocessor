package com.fafsa.editprocessor.input;

import java.util.List;

/** The application input has problems. Carries all of them, not just the first. */
public class InvalidApplicationException extends RuntimeException {

    private final List<InputError> errors;

    public InvalidApplicationException(List<InputError> errors) {
        super(errors.size() + (errors.size() == 1 ? " problem" : " problems") + " with the application");
        this.errors = List.copyOf(errors);
    }

    public List<InputError> getErrors() {
        return errors;
    }
}
