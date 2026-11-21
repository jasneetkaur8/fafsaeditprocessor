package com.fafsa.editprocessor.input;

import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs after Bean Validation (see {@link FafsaApplication}) and before the edit rules: checks
 * that {@code income} has at least one of {@code studentIncome} or {@code parentIncome}, then fills
 * in defaults for information the application left out.
 *
 * <p>Defaulted when missing or blank, each reported back as an {@link AppliedDefault}:
 * <ul>
 *   <li>{@code dependencyStatus} → {@code independent}</li>
 *   <li>{@code maritalStatus} → {@value #DEFAULT_MARITAL_STATUS}</li>
 *   <li>{@code household.numberInHousehold} and {@code household.numberInCollege} → 1 each</li>
 * </ul>
 */
@Component
public class ApplicationValidator {

    public static final DependencyStatus DEFAULT_DEPENDENCY_STATUS = DependencyStatus.INDEPENDENT;
    public static final String DEFAULT_MARITAL_STATUS = "single";
    public static final int DEFAULT_HOUSEHOLD_COUNT = 1;

    /**
     * Returns the application with defaults filled in, plus the defaults that were applied.
     *
     * @throws IncompleteApplicationException if neither income amount is provided
     */
    public ValidatedApplication validate(FafsaApplication application) {
        Income income = application.income();
        if (income == null || (income.studentIncome() == null && income.parentIncome() == null)) {
            throw new IncompleteApplicationException(List.of("income"));
        }

        List<AppliedDefault> defaults = new ArrayList<>();
        DependencyStatus dependencyStatus = application.dependencyStatus();
        if (dependencyStatus == null) {
            dependencyStatus = DEFAULT_DEPENDENCY_STATUS;
            defaults.add(new AppliedDefault("dependencyStatus", dependencyStatus,
                    "Dependency status not provided; defaulted to " + dependencyStatus));
        }
        String maritalStatus = application.maritalStatus();
        if (isBlank(maritalStatus)) {
            maritalStatus = DEFAULT_MARITAL_STATUS;
            defaults.add(new AppliedDefault("maritalStatus", maritalStatus,
                    "Marital status not provided; defaulted to " + maritalStatus));
        }
        Household household = withHouseholdDefaults(application.household(), defaults);

        FafsaApplication completed = new FafsaApplication(
                application.studentInfo(),
                dependencyStatus,
                maritalStatus,
                application.spouseInfo(),
                household,
                application.income(),
                application.stateOfResidence());
        return new ValidatedApplication(completed, List.copyOf(defaults));
    }

    private static Household withHouseholdDefaults(Household household, List<AppliedDefault> defaults) {
        Integer inHousehold = household == null ? null : household.numberInHousehold();
        Integer inCollege = household == null ? null : household.numberInCollege();
        if (inHousehold == null) {
            inHousehold = DEFAULT_HOUSEHOLD_COUNT;
            defaults.add(new AppliedDefault("household.numberInHousehold", inHousehold,
                    "Number in household not provided; defaulted to " + inHousehold));
        }
        if (inCollege == null) {
            inCollege = DEFAULT_HOUSEHOLD_COUNT;
            defaults.add(new AppliedDefault("household.numberInCollege", inCollege,
                    "Number in college not provided; defaulted to " + inCollege));
        }
        return new Household(inHousehold, inCollege);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
