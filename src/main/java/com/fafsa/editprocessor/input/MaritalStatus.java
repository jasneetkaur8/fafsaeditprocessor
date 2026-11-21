package com.fafsa.editprocessor.input;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The student's marital status. In JSON it is exactly {@code "single"} or {@code "married"}; any
 * other value (including other casing, a number or {@code ""}) is reported as an input error.
 */
public enum MaritalStatus {
    SINGLE("single"),
    MARRIED("married");

    private final String value;

    MaritalStatus(String value) {
        this.value = value;
    }

    /** The JSON form, also what rules compare against, e.g. {@code maritalStatus == "married"}. */
    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
