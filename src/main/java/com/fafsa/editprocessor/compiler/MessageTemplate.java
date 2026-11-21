package com.fafsa.editprocessor.compiler;

import com.fafsa.editprocessor.input.ApplicationData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {@code ${field.path}} placeholders in rule messages with the application's values. */
public final class MessageTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    static final String NOT_PROVIDED = "not provided";

    private MessageTemplate() {
    }

    /** Field paths used as placeholders in a message, in order of appearance. */
    public static List<String> placeholders(String message) {
        List<String> paths = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(message);
        while (m.find()) {
            paths.add(m.group(1).trim());
        }
        return paths;
    }

    public static String render(String message, ApplicationData data) {
        Matcher m = PLACEHOLDER.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = data.lookup(m.group(1).trim()).map(MessageTemplate::format).orElse(NOT_PROVIDED);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Money is held as a double; show 5000.0 as 5000 and 12.50 as 12.5. */
    private static String format(Object value) {
        if (value instanceof Double d && !d.isNaN() && !d.isInfinite()) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
