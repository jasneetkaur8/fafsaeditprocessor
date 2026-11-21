package com.fafsa.editprocessor.config;

import com.fafsa.editprocessor.api.FafsaApplicationMessageConverter;
import com.fafsa.editprocessor.input.ApplicationValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link FafsaApplicationMessageConverter} first, so it reads every FafsaApplication request body. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectMapper mapper;
    private final ApplicationValidator applicationValidator;

    public WebConfig(ObjectMapper mapper, ApplicationValidator applicationValidator) {
        this.mapper = mapper;
        this.applicationValidator = applicationValidator;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new FafsaApplicationMessageConverter(mapper, applicationValidator));
    }
}
