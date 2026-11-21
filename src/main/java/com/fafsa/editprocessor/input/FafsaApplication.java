package com.fafsa.editprocessor.input;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FAFSA application as received in the request body.
 *
 * <p>{@link ApplicationValidator} binds the request to this record field by field and reports
 * every problem in one response: values of the wrong type (text for a number, a date not in
 * {@code yyyy-MM-dd} form, a {@link DependencyStatus} or {@link MaritalStatus} outside its values), missing
 * required information (the Bean Validation annotations below), income amounts with more than 2
 * decimal places, and income with no amount.
 * Other fields may be missing: {@link ApplicationValidator} defaults them, and the rules decide
 * on the rest.
 */
public record FafsaApplication(
        @NotNull @Valid StudentInfo studentInfo,
        DependencyStatus dependencyStatus,
        MaritalStatus maritalStatus,
        SpouseInfo spouseInfo,
        Household household,
        @NotNull @Valid Income income,
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

    /** Required; {@link ApplicationValidator} also checks that at least one amount is provided. */
    public record Income(
            @Digits(integer = 15, fraction = 2, message = MONEY) BigDecimal studentIncome,
            @Digits(integer = 15, fraction = 2, message = MONEY) BigDecimal parentIncome) {

        static final String MONEY = "must be an amount with at most 2 decimal places";
    }
}
