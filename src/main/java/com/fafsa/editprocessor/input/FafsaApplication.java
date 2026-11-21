package com.fafsa.editprocessor.input;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * A FAFSA application as received in the request body.
 *
 * <p>{@link ApplicationValidator} binds the request to this record field by field and reports
 * every problem in one response: values of the wrong type (text for a number, a date not in
 * {@code yyyy-MM-dd} form, a {@link DependencyStatus} or {@link MaritalStatus} outside its values), missing
 * required information and household counts below 1 (the Bean Validation annotations below), and
 * income with no amount.
 * Other fields may be missing: {@link ApplicationValidator} defaults them, and the rules decide
 * on the rest.
 */
public record FafsaApplication(
        @NotNull @Valid StudentInfo studentInfo,
        DependencyStatus dependencyStatus,
        MaritalStatus maritalStatus,
        SpouseInfo spouseInfo,
        @Valid Household household,
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

    /** Each count, when given, must be greater than 0. */
    public record Household(
            @Positive Integer numberInHousehold,
            @Positive Integer numberInCollege) {
    }

    /**
     * Required; {@link ApplicationValidator} also checks that at least one amount is provided.
     * Amounts keep at most 2 decimal places. Further digits are dropped toward the lower amount
     * (5000.129 becomes 5000.12), so a negative amount stays negative: -0.001 becomes -0.01, not
     * 0.00, and the {@code NON_NEGATIVE_INCOME} rule still reports it.
     */
    public record Income(
            BigDecimal studentIncome,
            BigDecimal parentIncome) {

        public Income {
            studentIncome = toCents(studentIncome);
            parentIncome = toCents(parentIncome);
        }

        private static BigDecimal toCents(BigDecimal amount) {
            return amount != null && amount.scale() > 2 ? amount.setScale(2, RoundingMode.FLOOR) : amount;
        }
    }
}
