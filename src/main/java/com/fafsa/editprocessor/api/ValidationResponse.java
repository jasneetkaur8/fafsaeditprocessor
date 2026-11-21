package com.fafsa.editprocessor.api;

import com.fafsa.editprocessor.input.AppliedDefault;
import java.util.List;

/**
 * Result of validating one application.
 *
 * @param status          overall outcome, e.g. {@code VALID}
 * @param message         human-readable summary
 * @param defaultsApplied values the application left out that were filled in with defaults;
 *                        empty when nothing was defaulted
 */
public record ValidationResponse(String status, String message, List<AppliedDefault> defaultsApplied) {

    public static ValidationResponse valid(List<AppliedDefault> defaultsApplied) {
        return new ValidationResponse("VALID", "Valid application", List.copyOf(defaultsApplied));
    }
}
