package com.fafsa.editprocessor.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes JSON binding strict: a request must match the Java types exactly.
 *
 * <p>Jackson's defaults quietly repair bad input. Each case below is turned into a 400 instead:
 * <ul>
 *   <li>unknown fields (default: silently dropped)</li>
 *   <li>duplicate keys (default: last one wins)</li>
 *   <li>content after the JSON value (default: ignored)</li>
 *   <li>{@code "4"} for a number (default: parsed), {@code 4.7} for an integer (default: truncated)</li>
 *   <li>a number or boolean for a text field (default: converted to text)</li>
 *   <li>{@code ""} for a non-text field (default: {@code null})</li>
 *   <li>a number or array for a date (default: epoch day / {@code [y,m,d]})</li>
 *   <li>a number for an enum (default: taken as the constant's position)</li>
 * </ul>
 */
@Configuration
public class StrictJsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        // Requests are read into a JSON tree first: keep money exact, not a double
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .featuresToDisable(
                        DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .postConfigurer(mapper -> {
                    mapper.coercionConfigDefaults()
                            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                    mapper.coercionConfigFor(LogicalType.Textual)
                            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                })
                .deserializerByType(LocalDate.class, new StrictLocalDateDeserializer());
    }

    /**
     * Accepts only a {@code "yyyy-MM-dd"} string holding a real calendar date. The default
     * deserializer also accepts epoch-day numbers and {@code [y, m, d]} arrays, even when non-lenient.
     */
    static final class StrictLocalDateDeserializer extends StdScalarDeserializer<LocalDate> {

        StrictLocalDateDeserializer() {
            super(LocalDate.class);
        }

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return (LocalDate) ctxt.handleUnexpectedToken(LocalDate.class, p);
            }
            String text = p.getText();
            try {
                // ISO_LOCAL_DATE resolves STRICTly, so 2003-02-30 is rejected rather than adjusted
                return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                return (LocalDate) ctxt.handleWeirdStringValue(LocalDate.class, text, "expected yyyy-MM-dd");
            }
        }
    }
}
