package com.fafsa.editprocessor.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans the rules need: their settings, and the clock that defines "today" for age checks. */
@Configuration
@EnableConfigurationProperties(RulesProperties.class)
public class RulesConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
