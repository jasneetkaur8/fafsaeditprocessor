package com.fafsa.editprocessor.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fafsa.editprocessor.input.FafsaApplication.StudentInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ApplicationValidatorTest {

    private static final StudentInfo STUDENT =
            new StudentInfo("Jane", "Smith", "123456789", LocalDate.of(2003, 5, 15));
    private static final Income INCOME = new Income(new BigDecimal("5000"), null);

    private final ApplicationValidator validator = new ApplicationValidator();

    @Test
    void completeApplicationIsReturnedUnchangedWithNoDefaults() {
        FafsaApplication app = new FafsaApplication(STUDENT, "dependent", "married", null,
                new Household(4, 2), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application()).isEqualTo(app);
        assertThat(result.defaultsApplied()).isEmpty();
    }

    @Test
    void missingOptionalFieldsAreDefaultedAndReported() {
        FafsaApplication app = new FafsaApplication(STUDENT, null, null, null, null, INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().dependencyStatus()).isEqualTo("independent");
        assertThat(result.application().maritalStatus()).isEqualTo("single");
        assertThat(result.application().household()).isEqualTo(new Household(1, 1));
        assertThat(result.defaultsApplied())
                .extracting(AppliedDefault::field, AppliedDefault::value)
                .containsExactly(
                        tuple("dependencyStatus", "independent"),
                        tuple("maritalStatus", "single"),
                        tuple("household.numberInHousehold", 1),
                        tuple("household.numberInCollege", 1));
    }

    @Test
    void blankStatusesAreDefaulted() {
        FafsaApplication app = new FafsaApplication(STUDENT, "  ", "", null,
                new Household(3, 1), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().dependencyStatus()).isEqualTo("independent");
        assertThat(result.application().maritalStatus()).isEqualTo("single");
        assertThat(result.defaultsApplied()).extracting(AppliedDefault::field)
                .containsExactly("dependencyStatus", "maritalStatus");
    }

    @Test
    void onlyTheMissingHouseholdCountIsDefaulted() {
        FafsaApplication app = new FafsaApplication(STUDENT, "independent", "single", null,
                new Household(3, null), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().household()).isEqualTo(new Household(3, 1));
        assertThat(result.defaultsApplied()).extracting(AppliedDefault::field)
                .containsExactly("household.numberInCollege");
    }

    @Test
    void missingRequiredSectionsAreAllReported() {
        FafsaApplication app = new FafsaApplication(null, null, null, null, null, null, null);

        assertThatThrownBy(() -> validator.validate(app))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, ex ->
                        assertThat(ex.getMissingFields())
                                .containsExactly("studentInfo", "income", "stateOfResidence"));
    }

    @Test
    void missingRequiredSubfieldsAreAllReported() {
        FafsaApplication app = new FafsaApplication(
                new StudentInfo(" ", null, "", null), null, null, null, null,
                new Income(null, new BigDecimal("65000")), " ");

        assertThatThrownBy(() -> validator.validate(app))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, ex ->
                        assertThat(ex.getMissingFields()).containsExactly(
                                "studentInfo.firstName",
                                "studentInfo.lastName",
                                "studentInfo.ssn",
                                "studentInfo.dateOfBirth",
                                "income.studentIncome",
                                "stateOfResidence"));
    }
}
