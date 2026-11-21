package com.fafsa.editprocessor.input;

import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fafsa.editprocessor.input.FafsaApplication.StudentInfo;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * First check on a received application: makes sure the required information is present and
 * fills in defaults for the optional parts, before any edit rule runs.
 *
 * <p>Required (blank text counts as missing):
 * <ul>
 *   <li>{@code studentInfo} with {@code firstName}, {@code lastName}, {@code ssn} and
 *       {@code dateOfBirth}</li>
 *   <li>{@code income} with {@code studentIncome}; {@code parentIncome} depends on dependency
 *       status, so the edit rules decide on it</li>
 *   <li>{@code stateOfResidence}</li>
 * </ul>
 *
 * <p>Defaulted when missing or blank, each reported back as an {@link AppliedDefault}:
 * <ul>
 *   <li>{@code dependencyStatus} → {@value #DEFAULT_DEPENDENCY_STATUS}</li>
 *   <li>{@code maritalStatus} → {@value #DEFAULT_MARITAL_STATUS}</li>
 *   <li>{@code household.numberInHousehold} and {@code household.numberInCollege} → 1 each</li>
 * </ul>
 *
 * <p>Value checks (formats, allowed codes, cross-field consistency) are left to the edit rules.
 */
@Component
public class ApplicationValidator {

    public static final String DEFAULT_DEPENDENCY_STATUS = "independent";
    public static final String DEFAULT_MARITAL_STATUS = "single";
    public static final int DEFAULT_HOUSEHOLD_COUNT = 1;

    /**
     * Returns the application with defaults filled in, plus the defaults that were applied.
     *
     * @throws IncompleteApplicationException listing every missing required field
     */
    public ValidatedApplication validate(FafsaApplication application) {
        List<String> missing = new ArrayList<>();
        checkStudentInfo(application.studentInfo(), missing);
        checkIncome(application.income(), missing);
        if (isBlank(application.stateOfResidence())) {
            missing.add("stateOfResidence");
        }
        if (!missing.isEmpty()) {
            throw new IncompleteApplicationException(missing);
        }

        List<AppliedDefault> defaults = new ArrayList<>();
        String dependencyStatus = application.dependencyStatus();
        if (isBlank(dependencyStatus)) {
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

    private static void checkStudentInfo(StudentInfo student, List<String> missing) {
        if (student == null) {
            missing.add("studentInfo");
            return;
        }
        if (isBlank(student.firstName())) {
            missing.add("studentInfo.firstName");
        }
        if (isBlank(student.lastName())) {
            missing.add("studentInfo.lastName");
        }
        if (isBlank(student.ssn())) {
            missing.add("studentInfo.ssn");
        }
        if (student.dateOfBirth() == null) {
            missing.add("studentInfo.dateOfBirth");
        }
    }

    private static void checkIncome(Income income, List<String> missing) {
        if (income == null) {
            missing.add("income");
        } else if (income.studentIncome() == null) {
            missing.add("income.studentIncome");
        }
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
