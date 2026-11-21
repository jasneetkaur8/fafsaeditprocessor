package com.fafsa.editprocessor.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validator behaviour. Strict JSON settings (coercion etc.) are covered by FafsaEditResourceTest. */
class ApplicationValidatorTest {

    private static final String STUDENT = """
            "studentInfo": {"firstName": "Jane", "lastName": "Smith", "ssn": "123456789", "dateOfBirth": "2003-05-15"}""";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApplicationValidator validator = new ApplicationValidator(
            mapper, Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void completeApplicationIsReturnedWithNoDefaults() {
        ValidatedApplication result = check(json("""
                {%s, "dependencyStatus": "dependent", "maritalStatus": "married",
                 "household": {"numberInHousehold": 4, "numberInCollege": 2},
                 "income": {"studentIncome": 5000}, "stateOfResidence": "CA"}
                """.formatted(STUDENT)));

        assertThat(result.application().dependencyStatus()).isEqualTo(DependencyStatus.DEPENDENT);
        assertThat(result.application().household()).isEqualTo(new Household(4, 2));
        assertThat(result.application().income()).isEqualTo(new Income(new BigDecimal("5000"), null));
        assertThat(result.defaultsApplied()).isEmpty();
    }

    @Test
    void missingOptionalFieldsAreDefaultedAndReported() {
        ValidatedApplication result = check(json("""
                {%s, "income": {"studentIncome": 5000}, "stateOfResidence": "CA"}
                """.formatted(STUDENT)));

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
    void onlyTheMissingHouseholdCountIsDefaulted() {
        ValidatedApplication result = check(json("""
                {%s, "household": {"numberInHousehold": 3}, "maritalStatus": " ",
                 "income": {"studentIncome": 5000}, "stateOfResidence": "CA"}
                """.formatted(STUDENT)));

        assertThat(result.application().household()).isEqualTo(new Household(3, 1));
        assertThat(result.defaultsApplied()).extracting(AppliedDefault::field)
                .containsExactly("dependencyStatus", "maritalStatus", "household.numberInCollege");
    }

    @Test
    void parentIncomeAloneIsEnough() {
        ValidatedApplication result = check(json("""
                {%s, "income": {"parentIncome": 65000}, "stateOfResidence": "CA"}
                """.formatted(STUDENT)));

        assertThat(result.application().income()).isEqualTo(new Income(null, new BigDecimal("65000")));
    }

    @Test
    void badValuesAndMissingFieldsAreReportedTogether() {
        assertThat(errorsOf("""
                {"dependencyStatus": "abc", "nickname": "JJ",
                 "studentInfo": {"firstName": " ", "dateOfBirth": "15/05/2003"},
                 "household": {"numberInHousehold": "four"},
                 "income": {}}
                """)).extracting(InputError::field, InputError::problem).containsExactly(
                        tuple("dependencyStatus", "must be one of: dependent, independent"),
                        tuple("household.numberInHousehold", "must be a valid value"),
                        tuple("income", "must have studentIncome or parentIncome"),
                        tuple("nickname", "is not a known field"),
                        tuple("stateOfResidence", "must not be blank"),
                        tuple("studentInfo.dateOfBirth", "must be a valid value"),
                        tuple("studentInfo.firstName", "must not be blank"),
                        tuple("studentInfo.lastName", "must not be blank"),
                        tuple("studentInfo.ssn", "must not be blank"));
    }

    @Test
    void textWhereAnObjectBelongsIsNotAlsoReportedAsMissing() {
        assertThat(errorsOf("""
                {"studentInfo": "Jane Smith", "income": {"studentIncome": 1}, "stateOfResidence": "CA"}
                """)).containsExactly(new InputError("studentInfo", "must be an object"));
    }

    @Test
    void badIncomeAmountIsNotAlsoReportedAsNoIncome() {
        assertThat(errorsOf("""
                {%s, "income": {"studentIncome": "lots"}, "stateOfResidence": "CA"}
                """.formatted(STUDENT))).containsExactly(new InputError("income.studentIncome", "must be a valid value"));
    }

    @Test
    void emptyApplicationListsEveryRequiredField() {
        Throwable thrown = catchThrowable(() -> validator.toApplication(json("{}")));

        assertThat(thrown).hasMessage("3 problems with the application");
        assertThat(((InvalidApplicationException) thrown).getErrors()).extracting(InputError::field)
                .containsExactly("income", "stateOfResidence", "studentInfo");
    }

    private List<InputError> errorsOf(String body) {
        Throwable thrown = catchThrowable(() -> validator.toApplication(json(body)));
        assertThat(thrown).isInstanceOf(InvalidApplicationException.class);
        return ((InvalidApplicationException) thrown).getErrors();
    }

    /** The two steps the API runs: deserialize and check, then default. */
    private ValidatedApplication check(ObjectNode body) {
        return validator.applyDefaults(validator.toApplication(body));
    }

    private ObjectNode json(String body) {
        try {
            return (ObjectNode) mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
