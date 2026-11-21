/**
 * <b>Step 6: engine.</b>
 *
 * <p>Runs a {@code CompiledRuleSet} against one application:
 * {@code requires} (fields missing: NOT_APPLICABLE), then {@code when} (false: NOT_APPLICABLE),
 * then {@code expect} (true: PASSED, false: FAILED). Collects every result. A runtime error marks that
 * rule ERRORED, and the application is then never reported as VALID.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code RuleEngine}</li>
 *   <li>{@code RuleOutcome}: PASSED, FAILED, NOT_APPLICABLE, ERRORED</li>
 *   <li>{@code ApplicationStatus}: VALID, VALID_WITH_WARNINGS, INVALID, INCOMPLETE</li>
 *   <li>{@code RuleResult}, {@code Violation}, {@code ValidationResult}, {@code ValidationSummary}</li>
 *   <li>{@code MessageRenderer}: fills {@code ${field.path}} placeholders in messages</li>
 * </ul>
 */
package com.fafsa.editprocessor.engine;
