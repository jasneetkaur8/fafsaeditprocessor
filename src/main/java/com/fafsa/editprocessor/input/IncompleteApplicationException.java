package com.fafsa.editprocessor.input;

import java.util.List;

/** Required information is missing, found by a check Bean Validation cannot express. */
public class IncompleteApplicationException extends RuntimeException {

    private final List<String> missingFields;

    public IncompleteApplicationException(List<String> missingFields) {
        super("Missing required fields: " + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    /** Paths of the missing fields, e.g. {@code income}. */
    public List<String> getMissingFields() {
        return missingFields;
    }
}
