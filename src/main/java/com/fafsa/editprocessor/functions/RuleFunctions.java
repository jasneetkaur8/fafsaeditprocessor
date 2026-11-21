package com.fafsa.editprocessor.functions;

import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Helper functions rules can call, beyond CEL's built-ins. Each is generic (tied to no particular
 * rule) and documented in RULE_AUTHORING.md.
 *
 * <ul>
 *   <li>{@code ageInYears(date)}: whole years from a {@code "yyyy-MM-dd"} date to today; negative
 *       for a future date</li>
 *   <li>{@code isBlank(text)}: true when the text is empty or only whitespace</li>
 * </ul>
 */
public final class RuleFunctions {

    private static final String AGE_IN_YEARS = "ageInYears";
    private static final String AGE_IN_YEARS_STRING = "ageInYears_string";
    private static final String IS_BLANK = "isBlank";
    private static final String IS_BLANK_STRING = "isBlank_string";

    private RuleFunctions() {
    }

    /** Declarations for the compiler, so expressions using these functions type-check. */
    public static List<CelFunctionDecl> declarations() {
        return List.of(
                CelFunctionDecl.newFunctionDeclaration(AGE_IN_YEARS,
                        CelOverloadDecl.newGlobalOverload(AGE_IN_YEARS_STRING, SimpleType.INT, SimpleType.STRING)),
                CelFunctionDecl.newFunctionDeclaration(IS_BLANK,
                        CelOverloadDecl.newGlobalOverload(IS_BLANK_STRING, SimpleType.BOOL, SimpleType.STRING)));
    }

    /** Implementations for the runtime. {@code clock} supplies "today", so tests can fix the date. */
    public static List<CelFunctionBinding> bindings(Clock clock) {
        return List.of(
                CelFunctionBinding.from(AGE_IN_YEARS_STRING, String.class, date -> ageInYears(date, clock)),
                CelFunctionBinding.from(IS_BLANK_STRING, String.class, String::isBlank));
    }

    private static Long ageInYears(String date, Clock clock) throws CelEvaluationException {
        try {
            return (long) Period.between(LocalDate.parse(date), LocalDate.now(clock)).getYears();
        } catch (DateTimeParseException e) {
            throw new CelEvaluationException("ageInYears: '" + date + "' is not a yyyy-MM-dd date", e);
        }
    }
}
