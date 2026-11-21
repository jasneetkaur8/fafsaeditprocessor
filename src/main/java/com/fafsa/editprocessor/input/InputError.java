package com.fafsa.editprocessor.input;

/**
 * One problem with the request input.
 *
 * @param field   path of the field, e.g. {@code studentInfo.ssn}
 * @param problem what is wrong, e.g. {@code must not be blank}
 */
public record InputError(String field, String problem) {
}
