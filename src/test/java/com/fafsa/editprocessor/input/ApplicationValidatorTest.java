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
        FafsaApplication app = new FafsaApplication(STUDENT, DependencyStatus.DEPENDENT, "married", null,
                new Household(4, 2), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application()).isEqualTo(app);
        assertThat(result.defaultsApplied()).isEmpty();
    }

    @Test
    void missingOptionalFieldsAreDefaultedAndReported() {
        FafsaApplication app = new FafsaApplication(STUDENT, null, null, null, null, INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().dependencyStatus()).isEqualTo(DependencyStatus.INDEPENDENT);
        assertThat(result.application().maritalStatus()).isEqualTo("single");
        assertThat(result.application().household()).isEqualTo(new Household(1, 1));
        assertThat(result.defaultsApplied())
                .extracting(AppliedDefault::field, AppliedDefault::value)
                .containsExactly(
                        tuple("dependencyStatus", DependencyStatus.INDEPENDENT),
                        tuple("maritalStatus", "single"),
                        tuple("household.numberInHousehold", 1),
                        tuple("household.numberInCollege", 1));
    }

    @Test
    void missingDependencyAndBlankMaritalStatusAreDefaulted() {
        FafsaApplication app = new FafsaApplication(STUDENT, null, "", null,
                new Household(3, 1), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().dependencyStatus()).isEqualTo(DependencyStatus.INDEPENDENT);
        assertThat(result.application().maritalStatus()).isEqualTo("single");
        assertThat(result.defaultsApplied()).extracting(AppliedDefault::field)
                .containsExactly("dependencyStatus", "maritalStatus");
    }

    @Test
    void onlyTheMissingHouseholdCountIsDefaulted() {
        FafsaApplication app = new FafsaApplication(STUDENT, DependencyStatus.INDEPENDENT, "single", null,
                new Household(3, null), INCOME, "CA");

        ValidatedApplication result = validator.validate(app);

        assertThat(result.application().household()).isEqualTo(new Household(3, 1));
        assertThat(result.defaultsApplied()).extracting(AppliedDefault::field)
                .containsExactly("household.numberInCollege");
    }

    @Test
    void incomeWithNeitherAmountIsRejected() {
        FafsaApplication app = new FafsaApplication(STUDENT, null, null, null, null, new Income(null, null), "CA");

        assertThatThrownBy(() -> validator.validate(app))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, ex ->
                        assertThat(ex.getMissingFields()).containsExactly("income"));
    }

    @Test
    void parentIncomeAloneIsEnough() {
        FafsaApplication app = new FafsaApplication(STUDENT, null, null, null, null,
                new Income(null, new BigDecimal("65000")), "CA");

        assertThat(validator.validate(app).application().income())
                .isEqualTo(new Income(null, new BigDecimal("65000")));
    }
}
