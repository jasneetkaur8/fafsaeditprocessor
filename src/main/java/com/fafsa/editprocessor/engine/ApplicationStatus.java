package com.fafsa.editprocessor.engine;

/** Overall result for one application. */
public enum ApplicationStatus {
    /** No rule failed (INFO-level failures may still be listed). */
    VALID,
    /** Only WARNING-level rules failed. */
    VALID_WITH_WARNINGS,
    /** An ERROR-level rule failed, or a rule could not be evaluated. */
    INVALID
}
