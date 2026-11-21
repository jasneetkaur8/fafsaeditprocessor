package com.fafsa.editprocessor.input;

import com.fafsa.editprocessor.input.FafsaApplication.Household;
import com.fafsa.editprocessor.input.FafsaApplication.Income;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns a request body into a {@link FafsaApplication}, reporting every problem at once
 * ({@link #toApplication}), and fills in defaults ({@link #applyDefaults}).
 *
 * <p>{@link #toApplication} is what deserializes the request body: Spring calls it through
 * {@code FafsaApplicationMessageConverter} before the controller runs. It checks:
 * <ol>
 *   <li><b>Values</b>: each field is bound to its type in {@link FafsaApplication} on its own, with
 *       the strict JSON settings, so one bad value does not hide the others. Reported: unknown
 *       fields, wrong types, bad dates, values outside an enum (naming the allowed values), text
 *       where an object belongs.</li>
 *   <li><b>Required fields</b>: the Bean Validation annotations on {@link FafsaApplication}
 *       ({@code studentInfo} and its fields, {@code income}, {@code stateOfResidence}).</li>
 *   <li><b>Income</b>: at least one of {@code studentIncome} or {@code parentIncome}.</li>
 * </ol>
 * A field with a bad value is not also reported as missing.
 *
 * <p>Defaulted when missing or blank, each reported back as an {@link AppliedDefault}:
 * <ul>
 *   <li>{@code dependencyStatus} → {@code independent}</li>
 *   <li>{@code maritalStatus} → {@value #DEFAULT_MARITAL_STATUS}</li>
 *   <li>{@code household.numberInHousehold} and {@code household.numberInCollege} → 1 each</li>
 * </ul>
 */
@Component
public class ApplicationValidator {

    public static final DependencyStatus DEFAULT_DEPENDENCY_STATUS = DependencyStatus.INDEPENDENT;
    public static final String DEFAULT_MARITAL_STATUS = "single";
    public static final int DEFAULT_HOUSEHOLD_COUNT = 1;

    static final String INVALID_VALUE = "must be a valid value";
    static final String UNKNOWN_FIELD = "is not a known field";
    static final String NOT_AN_OBJECT = "must be an object";
    static final String NO_INCOME = "must have studentIncome or parentIncome";

    private final ObjectMapper mapper;
    private final Validator validator;

    public ApplicationValidator(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Binds a request body to a {@link FafsaApplication} and checks it.
     *
     * @throws InvalidApplicationException listing every problem, sorted by field
     */
    public FafsaApplication toApplication(ObjectNode body) {
        List<InputError> errors = new ArrayList<>();
        FafsaApplication application = (FafsaApplication) bind(body, FafsaApplication.class, "", errors);

        for (ConstraintViolation<FafsaApplication> violation : validator.validate(application)) {
            addUnlessReported(errors, violation.getPropertyPath().toString(), violation.getMessage());
        }
        Income income = application.income();
        if (income != null && income.studentIncome() == null && income.parentIncome() == null) {
            addUnlessReported(errors, "income", NO_INCOME);
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(InputError::field).thenComparing(InputError::problem));
            throw new InvalidApplicationException(errors);
        }
        return application;
    }

    /** Binds {@code node} to {@code type}; a bad value is recorded and becomes {@code null}. */
    private Object bind(JsonNode node, Class<?> type, String path, List<InputError> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!type.isRecord()) {
            try {
                return mapper.treeToValue(node, type);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                errors.add(new InputError(path, type.isEnum() ? oneOf(type) : INVALID_VALUE));
                return null;
            }
        }
        if (!node.isObject()) {
            errors.add(new InputError(path, NOT_AN_OBJECT));
            return null;
        }
        RecordComponent[] components = type.getRecordComponents();
        Set<String> known = Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toSet());
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                errors.add(new InputError(child(path, name), UNKNOWN_FIELD));
            }
        });
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName();
            values[i] = bind(node.get(name), components[i].getType(), child(path, name), errors);
        }
        try {
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            return type.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create " + type.getSimpleName(), e);
        }
    }

    /** Skips a problem on a field already reported, or inside or around one (a bad value is not also "missing"). */
    private static void addUnlessReported(List<InputError> errors, String field, String problem) {
        boolean reported = errors.stream().map(InputError::field).anyMatch(existing -> existing.equals(field)
                || field.startsWith(existing + ".") || existing.startsWith(field + "."));
        if (!reported) {
            errors.add(new InputError(field, problem));
        }
    }

    /** e.g. "must be one of: dependent, independent" (an enum's {@code toString()} is its JSON value). */
    private static String oneOf(Class<?> enumType) {
        return "must be one of: " + Arrays.stream(enumType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }

    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /** Returns the application with defaults filled in, plus the defaults that were applied. */
    public ValidatedApplication applyDefaults(FafsaApplication application) {
        List<AppliedDefault> defaults = new ArrayList<>();
        DependencyStatus dependencyStatus = application.dependencyStatus();
        if (dependencyStatus == null) {
            dependencyStatus = DEFAULT_DEPENDENCY_STATUS;
            defaults.add(new AppliedDefault("dependencyStatus", dependencyStatus,
                    "Dependency status not provided; defaulted to " + dependencyStatus));
        }
        String maritalStatus = application.maritalStatus();
        if (isBlank(maritalStatus)) {
            maritalStatus = DEFAULT_MARITAL_STATUS;
            defaults.add(new AppliedDefault("maritalStatus", maritalStatus,
                    "Marital status not provided; defaulted to " + maritalStatus));
        }
        Household household = withHouseholdDefaults(application.household(), defaults);

        FafsaApplication completed = new FafsaApplication(
                application.studentInfo(),
                dependencyStatus,
                maritalStatus,
                application.spouseInfo(),
                household,
                application.income(),
                application.stateOfResidence());
        return new ValidatedApplication(completed, List.copyOf(defaults));
    }

    private static Household withHouseholdDefaults(Household household, List<AppliedDefault> defaults) {
        Integer inHousehold = household == null ? null : household.numberInHousehold();
        Integer inCollege = household == null ? null : household.numberInCollege();
        if (inHousehold == null) {
            inHousehold = DEFAULT_HOUSEHOLD_COUNT;
            defaults.add(new AppliedDefault("household.numberInHousehold", inHousehold,
                    "Number in household not provided; defaulted to " + inHousehold));
        }
        if (inCollege == null) {
            inCollege = DEFAULT_HOUSEHOLD_COUNT;
            defaults.add(new AppliedDefault("household.numberInCollege", inCollege,
                    "Number in college not provided; defaulted to " + inCollege));
        }
        return new Household(inHousehold, inCollege);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
