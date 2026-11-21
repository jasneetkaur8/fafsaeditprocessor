package com.fafsa.editprocessor.ruleset;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.stream.Collectors;

/**
 * Reads a YAML rule file into a {@link RuleSetDefinition}.
 *
 * <p>Strict: an unknown key (a typo such as {@code expct}) or a duplicate key is an error rather
 * than being silently ignored, so a mistyped rule can never quietly do nothing.
 */
public final class RuleSetParser {

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RuleSetParser() {
    }

    /** @throws IllegalArgumentException describing the problem and its line, if the YAML is unreadable */
    public static RuleSetDefinition parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("Rule set is empty");
        }
        try {
            RuleSetDefinition definition = YAML.readValue(yaml, RuleSetDefinition.class);
            if (definition == null) {
                throw new IllegalArgumentException("Rule set is empty");
            }
            return definition;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Rule set YAML is invalid" + where(e) + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * For a content problem (unknown key, wrong type) the field path, e.g. {@code rules[2].expct}:
     * Jackson's line number there points past the bad value. For a syntax error, the line.
     */
    private static String where(JsonProcessingException e) {
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String path = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? "." + ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining())
                    .substring(1);
            return " at " + path;
        }
        JsonLocation location = e.getLocation();
        return location != null && location.getLineNr() > 0 ? " (line " + location.getLineNr() + ")" : "";
    }
}
