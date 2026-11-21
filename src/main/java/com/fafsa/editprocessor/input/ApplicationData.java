package com.fafsa.editprocessor.input;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An application as plain values that CEL rule expressions can read.
 *
 * <p>Objects become {@code Map}s, whole numbers {@code Long}, other numbers (money) {@code Double},
 * dates {@code "yyyy-MM-dd"} strings. Fields that were not provided are left out of the map, so a
 * rule can test for them with {@code has(...)} or list them under {@code requires}.
 *
 * <p>Conversion is driven by the {@link FafsaApplication} record itself, so a field added there is
 * available to rules with no change here.
 */
public final class ApplicationData {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    static {
        // Same strictness as the API: 123 is not accepted where text is expected
        MAPPER.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private static final Map<String, Class<?>> TOP_LEVEL_FIELDS = Collections.unmodifiableMap(
            Arrays.stream(FafsaApplication.class.getRecordComponents())
                    .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType,
                            (a, b) -> a, LinkedHashMap::new)));

    private final Map<String, Object> values;

    private ApplicationData(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static ApplicationData from(FafsaApplication application) {
        Object converted = toCelValue(MAPPER.valueToTree(application));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = converted instanceof Map ? (Map<String, Object>) converted : Map.of();
        return new ApplicationData(map);
    }

    /**
     * Builds application data from loosely typed input (a rule's test case), binding it to
     * {@link FafsaApplication} first so unknown fields and wrong types are caught.
     *
     * @throws IllegalArgumentException naming the problem
     */
    public static ApplicationData fromMap(Map<String, ?> input) {
        try {
            return from(MAPPER.convertValue(input == null ? Map.of() : input, FafsaApplication.class));
        } catch (IllegalArgumentException e) {
            if (e.getCause() instanceof JsonMappingException mapping) {
                throw new IllegalArgumentException(mapping.getOriginalMessage(), e);
            }
            throw e;
        }
    }

    /** The application's provided values, keyed by top-level field name. */
    public Map<String, Object> values() {
        return values;
    }

    /** Value at a dot path such as {@code income.parentIncome}, if it was provided. */
    public Optional<Object> lookup(String path) {
        Object current = values;
        for (String part : path.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            current = map.get(part);
        }
        return Optional.ofNullable(current);
    }

    /** Names of the application's top-level fields, which rules use as variables. */
    public static Set<String> topLevelFields() {
        return TOP_LEVEL_FIELDS.keySet();
    }

    /** Whether a top-level field holds an object (such as {@code household}) rather than a single value. */
    public static boolean isObjectField(String name) {
        Class<?> type = TOP_LEVEL_FIELDS.get(name);
        return type != null && type.isRecord();
    }

    /** Whether a dot path such as {@code income.parentIncome} names a real application field. */
    public static boolean isKnownPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Class<?> type = FafsaApplication.class;
        for (String part : path.split("\\.", -1)) {
            if (!type.isRecord()) {
                return false;
            }
            Optional<Class<?>> next = Arrays.stream(type.getRecordComponents())
                    .filter(c -> c.getName().equals(part))
                    .<Class<?>>map(RecordComponent::getType)
                    .findFirst();
            if (next.isEmpty()) {
                return false;
            }
            type = next.get();
        }
        return true;
    }

    private static Object toCelValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Object value = toCelValue(entry.getValue());
                if (value != null) {
                    map.put(entry.getKey(), value);
                }
            });
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(toCelValue(item)));
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }
}
