package com.fafsa.editprocessor.ruleset;

/** How serious a failed rule is. Only {@link #ERROR} makes an application INVALID. */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
