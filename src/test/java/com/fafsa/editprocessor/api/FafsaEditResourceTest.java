package com.fafsa.editprocessor.api;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fafsa.editprocessor.config.StrictJsonConfig;
import com.fafsa.editprocessor.input.ApplicationValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FafsaEditResource.class)
@Import({StrictJsonConfig.class, ApplicationValidator.class})
class FafsaEditResourceTest {

    private static final String URL = "/api/v1/applications/validate";

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
                .andExpect(jsonPath("$.defaultsApplied").isEmpty());
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
            array instead of object   | [1, 2, 3]                                           | Request body must be a single JSON object
            trailing content          | {} {}                                               | Request body must be a single JSON object
            """)
    void noisyInputIsRejected(String scenario, String body, String expectedDetail) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed application"))
                .andExpect(jsonPath("$.detail").value(expectedDetail));
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
                .andExpect(status().isUnsupportedMediaType());
    }
}
