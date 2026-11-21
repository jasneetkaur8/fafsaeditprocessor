package com.fafsa.editprocessor.api;

import static org.hamcrest.Matchers.startsWith;
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
                .andExpect(jsonPath("$.defaultsApplied").isEmpty())
                .andExpect(jsonPath("$.rulesetVersion").value("2026-27.1"))
                .andExpect(jsonPath("$.violations").isEmpty())
                .andExpect(jsonPath("$.summary.rulesEvaluated").value(7))
                .andExpect(jsonPath("$.summary.passed").value(6))
                .andExpect(jsonPath("$.summary.notApplicable").value(1));
    }

    @Test
    void invalidApplicationFailsAllSevenRules() throws Exception {
        String body = Files.readString(Path.of("examples/invalid-application.json"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.message").value("Application failed 7 rules"))
                .andExpect(jsonPath("$.summary.failed").value(7))
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
    @CsvSource({"2012-11-20, VALID", "2012-11-21, INVALID"})
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
                .andExpect(jsonPath("$.defaultsApplied[*].field").value(org.hamcrest.Matchers.contains(
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
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.defaultsApplied.length()").value(4))
                .andExpect(jsonPath("$.defaultsApplied[0].field").value("dependencyStatus"))
                .andExpect(jsonPath("$.defaultsApplied[0].value").value("independent"))
                .andExpect(jsonPath("$.defaultsApplied[0].message")
                        .value("Dependency status not provided; defaulted to independent"))
                .andExpect(jsonPath("$.defaultsApplied[1].field").value("maritalStatus"))
                .andExpect(jsonPath("$.defaultsApplied[1].value").value("single"))
                .andExpect(jsonPath("$.defaultsApplied[2].field").value("household.numberInHousehold"))
                .andExpect(jsonPath("$.defaultsApplied[2].value").value(1))
                .andExpect(jsonPath("$.defaultsApplied[3].field").value("household.numberInCollege"))
                .andExpect(jsonPath("$.defaultsApplied[3].value").value(1));
    }

    /** Missing and null fields bind fine; the validator then reports every missing required field. */
    @Test
    void missingRequiredFieldsAreReported() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentInfo\": {\"ssn\": null}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Incomplete application"))
                .andExpect(jsonPath("$.detail").value("Missing required fields: studentInfo.firstName, "
                        + "studentInfo.lastName, studentInfo.ssn, studentInfo.dateOfBirth, income, "
                        + "stateOfResidence"))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.missingFields.length()").value(6))
                .andExpect(jsonPath("$.missingFields[0]").value("studentInfo.firstName"));
    }

    /** Every input Jackson would otherwise quietly accept or repair is rejected, naming the field. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            unknown top-level field   | {"nickname": "JJ"}                                  | Unknown field 'nickname'
            unknown nested field      | {"studentInfo": {"middleName": "Q"}}                | Unknown field 'studentInfo.middleName'
            text for integer          | {"household": {"numberInHousehold": "4"}}           | Invalid value for field 'household.numberInHousehold': expected Integer
            decimal for integer       | {"household": {"numberInCollege": 1.5}}             | Invalid value for field 'household.numberInCollege': expected Integer
            text for money            | {"income": {"parentIncome": "65000"}}               | Invalid value for field 'income.parentIncome': expected BigDecimal
            number for text           | {"studentInfo": {"ssn": 123456789}}                 | Invalid value for field 'studentInfo.ssn': expected String
            boolean for text          | {"stateOfResidence": true}                          | Invalid value for field 'stateOfResidence': expected String
            empty string for integer  | {"household": {"numberInHousehold": ""}}            | Invalid value for field 'household.numberInHousehold': expected Integer
            empty string for date     | {"studentInfo": {"dateOfBirth": ""}}                | Invalid value for field 'studentInfo.dateOfBirth': expected LocalDate
            number for date           | {"studentInfo": {"dateOfBirth": 12000}}             | Invalid value for field 'studentInfo.dateOfBirth': expected LocalDate
            array for date            | {"studentInfo": {"dateOfBirth": [2003, 5, 15]}}     | Invalid value for field 'studentInfo.dateOfBirth': expected LocalDate
            wrong date format         | {"studentInfo": {"dateOfBirth": "15/05/2003"}}      | Invalid value for field 'studentInfo.dateOfBirth': expected LocalDate
            impossible date           | {"studentInfo": {"dateOfBirth": "2003-02-30"}}      | Invalid value for field 'studentInfo.dateOfBirth': expected LocalDate
            object for text           | {"maritalStatus": {"value": "single"}}              | Invalid value for field 'maritalStatus': expected String
            unknown dependency status | {"dependencyStatus": "unknown"}                     | Invalid value for field 'dependencyStatus': expected one of dependent, independent
            dependency status casing  | {"dependencyStatus": "Dependent"}                   | Invalid value for field 'dependencyStatus': expected one of dependent, independent
            enum constant name        | {"dependencyStatus": "DEPENDENT"}                   | Invalid value for field 'dependencyStatus': expected one of dependent, independent
            empty dependency status   | {"dependencyStatus": ""}                            | Invalid value for field 'dependencyStatus': expected one of dependent, independent
            number for enum           | {"dependencyStatus": 0}                             | Invalid value for field 'dependencyStatus': expected one of dependent, independent
            array instead of object   | [1, 2, 3]                                           | Request body must be a single JSON object
            trailing content          | {} {}                                               | Request body must be a single JSON object
            """)
    void noisyInputIsRejected(String scenario, String body, String expectedDetail) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed application"))
                .andExpect(jsonPath("$.detail").value(expectedDetail))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist());
    }

    @Test
    void duplicateKeyIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateOfResidence\": \"CA\", \"stateOfResidence\": \"NY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Request body is not valid JSON: Duplicate field 'stateOfResidence'"));
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("Request body is not valid JSON: ")));
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
