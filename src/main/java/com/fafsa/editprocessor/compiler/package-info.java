/**
 * <b>Step 4: compiler and validator (the most important step).</b>
 *
 * <p>Turns a {@code RuleSetDefinition} into a ready-to-run {@code CompiledRuleSet}, or rejects it
 * with every problem listed. A rule set that fails here must never go live.
 *
 * <p>Checks: unique, non-blank rule ids; every {@code when} and {@code expect} compiles and returns a
 * boolean; referenced lists exist; and every embedded test case produces its expected outcome.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code RuleSetCompiler}: the compiler and validator</li>
 *   <li>{@code CompiledRule}, {@code CompiledRuleSet}: immutable and thread-safe; CEL programs are built once here</li>
 *   <li>{@code RuleSetValidationException}: carries the list of errors (rule id plus problem)</li>
 * </ul>
 */
package com.fafsa.editprocessor.compiler;
