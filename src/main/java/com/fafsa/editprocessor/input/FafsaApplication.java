package com.fafsa.editprocessor.input;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FAFSA application as received in the request body.
 *
 * <p>Checked in two steps before {@link ApplicationValidator} and the edit rules run, both a 400:
 * <ol>
 *   <li>JSON binding rejects values of the wrong type (text for a number, a date not in
 *       {@code yyyy-MM-dd} form, a {@link DependencyStatus} other than dependent/independent)</li>
 *   <li>Bean Validation rejects missing required information: {@code studentInfo} and all its
 *       fields, {@code income} and {@code stateOfResidence}</li>
 * </ol>
 * {@link ApplicationValidator} then checks that {@code income} has at least one amount.
 * Other fields may be missing: {@link ApplicationValidator} defaults them, and the rules decide
 * on the rest.
 */
public record FafsaApplication(
        @NotNull @Valid StudentInfo studentInfo,
        DependencyStatus dependencyStatus,
        String maritalStatus,
        SpouseInfo spouseInfo,
        Household household,
        @NotNull Income income,
        @NotBlank String stateOfResidence) {

    /** Required, with every field: blank text counts as missing. */
    public record StudentInfo(
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotBlank String ssn,
            @NotNull LocalDate dateOfBirth) {
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

    /** Required; {@link ApplicationValidator} checks that at least one amount is provided. */
    public record Income(
            BigDecimal studentIncome,
            BigDecimal parentIncome) {
    }
}
