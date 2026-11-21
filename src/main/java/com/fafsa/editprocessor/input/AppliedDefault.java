package com.fafsa.editprocessor.input;

/**
 * A value the application did not provide, which was filled in with a default.
 *
 * @param field   path of the defaulted field, e.g. {@code household.numberInHousehold}
 * @param value   the default that was used
 * @param message human-readable explanation
 */
public record AppliedDefault(String field, Object value, String message) {
}
