package com.fafsa.editprocessor.input;

import java.util.List;

/** Thrown when an application is missing required information. */
public class IncompleteApplicationException extends RuntimeException {

    private final List<String> missingFields;

    public IncompleteApplicationException(List<String> missingFields) {
        super("Missing required fields: " + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    /** Paths of the missing fields, e.g. {@code studentInfo.ssn}. */
    public List<String> getMissingFields() {
        return missingFields;
    }
}
