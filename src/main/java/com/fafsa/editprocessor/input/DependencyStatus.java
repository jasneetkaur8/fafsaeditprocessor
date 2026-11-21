package com.fafsa.editprocessor.input;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether the student is dependent on their parents. In JSON it is exactly {@code "dependent"} or
 * {@code "independent"}; any other value (including other casing, a number or {@code ""}) is a 400.
 */
public enum DependencyStatus {
    DEPENDENT("dependent"),
    INDEPENDENT("independent");

    private final String value;

    DependencyStatus(String value) {
        this.value = value;
    }

    /** The JSON form, also what rules compare against, e.g. {@code dependencyStatus == "dependent"}. */
    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
