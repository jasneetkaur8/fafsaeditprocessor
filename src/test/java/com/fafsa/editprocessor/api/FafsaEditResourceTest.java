package com.fafsa.editprocessor.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Runs against the real rule file, so these tests also prove it loads and its rules behave. */
@SpringBootTest
@AutoConfigureMockMvc
class FafsaEditResourceTest {

    private static final String URL = "/api/v1/applications/validate";

    /** Fixes "today" so the age rule gives the same answer whenever the tests run. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void validApplicationReturnsValid() throws Exception {
        String body = Files.readString(Path.of("examples/valid-application.json"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.message").value("Valid application"))
                .andExpect(content().json("{\"status\": \"VALID\", \"message\": \"Valid application\"}", true));
    }

    @Test
    void invalidApplicationFailsAllSevenRules() throws Exception {
        String body = Files.readString(Path.of("examples/invalid-application.json"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.message").value("Application failed 7 rules"))
                .andExpect(jsonPath("$.warnings").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.violations[*].ruleId").value(org.hamcrest.Matchers.contains(
                        "STUDENT_AGE", "SSN_FORMAT", "DEPENDENT_PARENT_INCOME", "NON_NEGATIVE_INCOME",
                        "HOUSEHOLD_COLLEGE_COUNT", "STATE_CODE", "MARRIED_SPOUSE_INFO")))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("Student must be at least 14 years old (date of birth: 2015-01-01)"))
                .andExpect(jsonPath("$.violations[0].field").value("studentInfo.dateOfBirth"))
                .andExpect(jsonPath("$.violations[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.violations[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.violations[3].message")
                        .value("Income cannot be negative (student income: -1000, parent income: not provided)"))
                .andExpect(jsonPath("$.violations[4].message")
                        .value("Number in college (5) cannot exceed number in household (2)"))
                .andExpect(jsonPath("$.violations[5].message")
                        .value("'XX' is not a valid US state abbreviation"));
    }

    @ParameterizedTest(name = "born {0}: {1}")
    @CsvSource({"2012-11-20, VALID_WITH_WARNINGS", "2012-11-21, INVALID"})
    void studentMustBeFourteenOnTheDayOfValidation(String dateOfBirth, String expectedStatus) throws Exception {
        String body = """
                {
                  "studentInfo": {"firstName": "Jane", "lastName": "Smith",
                                  "ssn": "123456789", "dateOfBirth": "%s"},
                  "income": {"studentIncome": 0},
                  "stateOfResidence": "CA"
                }
                """.formatted(dateOfBirth);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    void defaultedValuesAreWhatTheRulesSee() throws Exception {
        // numberInCollege 3 with no numberInHousehold: household defaults to 1, so the count rule fails
        String body = """
                {
                  "studentInfo": {"firstName": "Jane", "lastName": "Smith",
                                  "ssn": "123456789", "dateOfBirth": "2003-05-15"},
                  "household": {"numberInCollege": 3},
                  "income": {"studentIncome": 0},
                  "stateOfResidence": "CA"
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.violations[0].ruleId").value("HOUSEHOLD_COLLEGE_COUNT"))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("Number in college (3) cannot exceed number in household (1)"))
                .andExpect(jsonPath("$.warnings[*].field").value(org.hamcrest.Matchers.contains(
                        "dependencyStatus", "maritalStatus", "household.numberInHousehold")));
    }

    @Test
    void omittedOptionalFieldsAreDefaultedAndReported() throws Exception {
        String body = """
                {
                  "studentInfo": {"firstName": "Jane", "lastName": "Smith",
                                  "ssn": "123456789", "dateOfBirth": "2003-05-15"},
                  "income": {"studentIncome": 5000},
                  "stateOfResidence": "CA"
                }
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID_WITH_WARNINGS"))
                .andExpect(jsonPath("$.message").value("Valid application with 4 warnings"))
                .andExpect(jsonPath("$.violations").doesNotExist())
                .andExpect(jsonPath("$.warnings.length()").value(4))
                .andExpect(jsonPath("$.warnings[0].field").value("dependencyStatus"))
                .andExpect(jsonPath("$.warnings[0].value").value("independent"))
                .andExpect(jsonPath("$.warnings[0].message")
                        .value("Dependency status not provided; defaulted to independent"))
                .andExpect(jsonPath("$.warnings[1].field").value("maritalStatus"))
                .andExpect(jsonPath("$.warnings[1].value").value("single"))
                .andExpect(jsonPath("$.warnings[2].field").value("household.numberInHousehold"))
                .andExpect(jsonPath("$.warnings[2].value").value(1))
                .andExpect(jsonPath("$.warnings[3].field").value("household.numberInCollege"))
                .andExpect(jsonPath("$.warnings[3].value").value(1));
    }

    /** The example from the bug report: a bad enum value no longer hides the missing student info. */
    @Test
    void badValuesAndMissingFieldsAreReportedTogether() throws Exception {
        String body = """
                {"dependencyStatus": "abc", "maritalStatus": "single",
                 "household": {"numberInHousehold": "4", "numberInCollege": 1},
                 "income": {"studentIncome": 5000, "parentIncome": 65000},
                 "stateOfResidence": "CA", "nickname": "JJ"}
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("4 problems with the application"))
                // dependencyStatus has an error and numberInHousehold an error, so neither is defaulted
                .andExpect(jsonPath("$.warnings").doesNotExist())
                .andExpect(jsonPath("$.violations").doesNotExist())
                .andExpect(jsonPath("$.errors[*].field").value(contains(
                        "dependencyStatus", "household.numberInHousehold", "nickname", "studentInfo")))
                .andExpect(jsonPath("$.errors[*].problem").value(contains(
                        "must be one of: dependent, independent", "must be a whole number",
                        "is not a known field", "must not be null")))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist());
    }

    /** The example from the bug report: invalid input still says which defaults apply. */
    @Test
    void invalidInputStillListsTheDefaults() throws Exception {
        String body = """
                {"studentInfo": {"firstName": "Jane", "lastName": "Smith",
                                 "ssn": "123456789", "dateOfBirth": "2003-05-15"},
                 "maritalStatus": "single", "household": {"numberInHousehold": 4, "numberInCollege": 1},
                 "income": {}, "stateOfResidence": "CA"}
                """;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"status": "INVALID_INPUT", "message": "1 problem with the application",
                         "errors": [{"field": "income", "problem": "must have studentIncome or parentIncome"}],
                         "warnings": [{"field": "dependencyStatus", "value": "independent",
                                       "message": "Dependency status not provided; defaulted to independent"}]}
                        """, true));
    }

    @Test
    void everyMissingStudentInfoFieldIsReported() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentInfo\": {\"firstName\": \"  \", \"ssn\": null}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("income", "stateOfResidence",
                        "studentInfo.dateOfBirth", "studentInfo.firstName", "studentInfo.lastName", "studentInfo.ssn")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            no income amounts  | {}
            both amounts null  | {"studentIncome": null, "parentIncome": null}
            """)
    void incomeNeedsAtLeastOneAmount(String scenario, String incomeJson) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(withIncome(incomeJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("income")))
                .andExpect(jsonPath("$.errors[0].problem").value("must have studentIncome or parentIncome"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            3 decimal places        | {"studentIncome": 5000.123}                       | income.studentIncome
            3 decimal places parent | {"studentIncome": 1, "parentIncome": 65000.005} | income.parentIncome
            """)
    void incomeAllowsAtMostTwoDecimalPlaces(String scenario, String incomeJson, String field) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(withIncome(incomeJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains(field)))
                .andExpect(jsonPath("$.errors[0].problem").value("must be an amount with at most 2 decimal places"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            two decimal places      | {"studentIncome": 5000.25}
            one decimal place       | {"studentIncome": 5000.5}
            trailing zeros          | {"studentIncome": 5000.100}
            student income only | {"studentIncome": 5000}
            parent income only  | {"parentIncome": 65000}
            """)
    void validIncomeIsAccepted(String scenario, String incomeJson) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(withIncome(incomeJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID_WITH_WARNINGS"));
    }

    @Test
    void blankStateIsReported() throws Exception {
        String body = withIncome("{\"studentIncome\": 0}").replace("\"CA\"", "\"  \"");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("stateOfResidence")))
                .andExpect(jsonPath("$.errors[0].problem").value("must not be blank"));
    }

    /** Every input Jackson would otherwise quietly accept or repair is reported for its field. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            unknown top-level field   | {"nickname": "JJ"}                              | nickname                    | is not a known field
            unknown nested field      | {"studentInfo": {"middleName": "Q"}}            | studentInfo.middleName      | is not a known field
            text for integer          | {"household": {"numberInHousehold": "4"}}       | household.numberInHousehold | must be a whole number
            decimal for integer       | {"household": {"numberInCollege": 1.5}}         | household.numberInCollege   | must be a whole number
            text for money            | {"income": {"parentIncome": "65000"}}           | income.parentIncome         | must be a number
            number for text           | {"studentInfo": {"ssn": 123456789}}             | studentInfo.ssn             | must be text
            boolean for text          | {"stateOfResidence": true}                      | stateOfResidence            | must be text
            empty string for integer  | {"household": {"numberInHousehold": ""}}        | household.numberInHousehold | must be a whole number
            empty string for date     | {"studentInfo": {"dateOfBirth": ""}}            | studentInfo.dateOfBirth     | must be a valid date in yyyy-MM-dd format
            number for date           | {"studentInfo": {"dateOfBirth": 12000}}         | studentInfo.dateOfBirth     | must be a valid date in yyyy-MM-dd format
            array for date            | {"studentInfo": {"dateOfBirth": [2003, 5, 15]}} | studentInfo.dateOfBirth     | must be a valid date in yyyy-MM-dd format
            wrong date format         | {"studentInfo": {"dateOfBirth": "15/05/2003"}}  | studentInfo.dateOfBirth     | must be a valid date in yyyy-MM-dd format
            impossible date           | {"studentInfo": {"dateOfBirth": "2003-02-30"}}  | studentInfo.dateOfBirth     | must be a valid date in yyyy-MM-dd format
            object for text           | {"studentInfo": {"lastName": {"v": "Smith"}}}  | studentInfo.lastName        | must be text
            unknown marital status    | {"maritalStatus": "divorced"}                   | maritalStatus               | must be one of: single, married
            marital status casing     | {"maritalStatus": "Married"}                    | maritalStatus               | must be one of: single, married
            empty marital status      | {"maritalStatus": ""}                           | maritalStatus               | must be one of: single, married
            object for enum           | {"maritalStatus": {"value": "single"}}          | maritalStatus               | must be one of: single, married
            text for object           | {"household": "big"}                            | household                   | must be an object
            unknown dependency status | {"dependencyStatus": "unknown"}                 | dependencyStatus            | must be one of: dependent, independent
            dependency status casing  | {"dependencyStatus": "Dependent"}               | dependencyStatus            | must be one of: dependent, independent
            enum constant name        | {"dependencyStatus": "DEPENDENT"}               | dependencyStatus            | must be one of: dependent, independent
            empty dependency status   | {"dependencyStatus": ""}                        | dependencyStatus            | must be one of: dependent, independent
            number for enum           | {"dependencyStatus": 0}                         | dependencyStatus            | must be one of: dependent, independent
            """)
    void noisyInputIsReportedForItsField(String scenario, String body, String field, String problem) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].problem").value(contains(problem)));
    }

    /** Problems with the body as a whole cannot be tied to a field, so they are reported alone. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            array instead of object | [1, 2, 3]
            trailing content        | {} {}
            duplicate key           | {"stateOfResidence": "CA", "stateOfResidence": "NY"}
            broken JSON             | { not json
            """)
    void malformedBodyIsRejected(String scenario, String body) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("Request body must be a single valid JSON object"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist());
    }

    private static String withIncome(String incomeJson) {
        return """
                {"studentInfo": {"firstName": "Jane", "lastName": "Smith",
                                 "ssn": "123456789", "dateOfBirth": "2003-05-15"},
                 "income": %s, "stateOfResidence": "CA"}
                """.formatted(incomeJson);
    }

    @Test
    void emptyBodyIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonJsonContentTypeIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist());
    }

    @Test
    void unknownPathDoesNotRevealThePath() throws Exception {
        mvc.perform(post("/api/v1/nowhere").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("nowhere"))));
    }
}
