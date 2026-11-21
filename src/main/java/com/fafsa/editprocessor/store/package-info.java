/**
 * <b>Step 8: rule store and runtime activation.</b>
 *
 * <p>Holds the active {@code CompiledRuleSet} behind an {@code AtomicReference}. Each request takes
 * one snapshot, so a reload part-way through a request can never mix old and new rules.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code RuleSetStore}: load at startup (path from {@code fafsa.rules.location}), activate
 *       (compile, then swap), keep version history, roll back</li>
 *   <li>Save each activated rule set under {@code fafsa.rules.storage-dir} so a restart keeps it</li>
 * </ul>
 */
package com.fafsa.editprocessor.store;
