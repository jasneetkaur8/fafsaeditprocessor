package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.ApplicationValidator;
import com.fafsa.editprocessor.input.FafsaApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Deserializes a JSON request body into a {@link FafsaApplication} for {@code @RequestBody}.
 *
 * <p>Used instead of Spring's Jackson converter, which stops at the first bad value: the JSON is
 * parsed with the same strict mapper, then {@link ApplicationValidator#toApplication} binds and
 * checks every field so all problems are reported together. Read-only; responses are still
 * written by the Jackson converter.
 */
public class FafsaApplicationMessageConverter extends AbstractHttpMessageConverter<FafsaApplication> {

    private final ObjectMapper mapper;
    private final ApplicationValidator applicationValidator;

    public FafsaApplicationMessageConverter(ObjectMapper mapper, ApplicationValidator applicationValidator) {
        super(MediaType.APPLICATION_JSON);
        this.mapper = mapper;
        this.applicationValidator = applicationValidator;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return FafsaApplication.class.equals(clazz);
    }

    @Override
    protected boolean canWrite(MediaType mediaType) {
        return false;
    }

    @Override
    protected FafsaApplication readInternal(Class<? extends FafsaApplication> clazz, HttpInputMessage inputMessage)
            throws IOException {
        JsonNode body;
        try {
            body = mapper.readTree(inputMessage.getBody());
        } catch (JsonProcessingException e) {
            throw new HttpMessageNotReadableException("Request body is not valid JSON", e, inputMessage);
        }
        if (!(body instanceof ObjectNode object)) {
            throw new HttpMessageNotReadableException("Request body is not a JSON object", inputMessage);
        }
        return applicationValidator.toApplication(object);
    }

    @Override
    protected void writeInternal(FafsaApplication application, HttpOutputMessage outputMessage) {
        throw new UnsupportedOperationException("FafsaApplication is only read from requests");
    }
}
