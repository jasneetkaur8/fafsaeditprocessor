package com.fafsa.editprocessor.input;

import java.util.List;

/**
 * An application that has all required information, with defaults filled in.
 *
 * @param application     the application, including any defaulted values
 * @param defaultsApplied the defaults that were filled in, in the order they were applied
 */
public record ValidatedApplication(FafsaApplication application, List<AppliedDefault> defaultsApplied) {
}
