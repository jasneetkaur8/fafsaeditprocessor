/**
 * <b>Step 5: helper functions.</b>
 *
 * <p>The only rule-related code: a few generic, documented functions that CEL lacks, registered
 * with the CEL compiler and runtime. Each one is tied to no particular rule, and each must be
 * documented in RULE_AUTHORING.md with an example.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code ageInYears(dateString)}: whole years between a YYYY-MM-DD date and today</li>
 *   <li>{@code isBlank(string)}</li>
 *   <li>a {@code today} variable, supplied from an injected {@code java.time.Clock} so tests can fix the date</li>
 * </ul>
 */
package com.fafsa.editprocessor.functions;
