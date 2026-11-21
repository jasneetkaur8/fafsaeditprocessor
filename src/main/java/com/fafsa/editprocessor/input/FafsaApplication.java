package com.fafsa.editprocessor.input;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FAFSA application as received in the request body.
 *
 * <p>Every field is nullable: a missing field is left {@code null} so the edit rules, not JSON
 * binding, decide whether it is required. Only values of the wrong type (for example text where a
 * number is expected, or a date not in {@code yyyy-MM-dd} form) are rejected at binding time.
 */
public record FafsaApplication(
        StudentInfo studentInfo,
        String dependencyStatus,
        String maritalStatus,
        SpouseInfo spouseInfo,
        Household household,
        Income income,
        String stateOfResidence) {

    public record StudentInfo(
            String firstName,
            String lastName,
            String ssn,
            LocalDate dateOfBirth) {
    }

    /** Spouse details; expected only when {@code maritalStatus} is married. */
    public record SpouseInfo(
            String firstName,
            String lastName,
            String ssn) {
    }

    public record Household(
            Integer numberInHousehold,
            Integer numberInCollege) {
    }

    public record Income(
            BigDecimal studentIncome,
            BigDecimal parentIncome) {
    }
}
