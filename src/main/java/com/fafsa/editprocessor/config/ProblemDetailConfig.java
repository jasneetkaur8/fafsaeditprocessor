package com.fafsa.editprocessor.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

/**
 * Leaves {@code type} and {@code instance} out of error responses: {@code type} is always
 * {@code about:blank} and tells the client nothing, and {@code instance} reveals the request path.
 *
 * <p>Spring fills {@code instance} with the request path as the response is written, so it cannot
 * be cleared in an exception handler; instead both are left out when the JSON is written. Extending
 * Spring's own mix-in keeps its other behaviour, such as writing extra properties (for example
 * {@code missingFields}) at the top level.
 */
@Configuration
public class ProblemDetailConfig {

    @JsonIgnoreProperties({"type", "instance"})
    interface ProblemDetailWithoutTypeOrInstance extends ProblemDetailJacksonMixin {
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer problemDetailWithoutTypeOrInstance() {
        return builder -> builder.mixIn(ProblemDetail.class, ProblemDetailWithoutTypeOrInstance.class);
    }
}
