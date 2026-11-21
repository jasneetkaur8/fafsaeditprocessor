/**
 * <b>Step 3: rule-set format.</b>
 *
 * <p>Plain Java records that mirror the YAML rule file, read with Jackson YAML. No logic lives here.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code RuleSetDefinition}: rulesetVersion, named lists, rules</li>
 *   <li>{@code RuleDefinition}: id, severity, description, requires, when, expect, field,
 *       message, remediation, tests</li>
 *   <li>{@code RuleTestCase}: an input and its expected outcome</li>
 *   <li>{@code Severity}: ERROR, WARNING, INFO</li>
 * </ul>
 */
package com.fafsa.editprocessor.ruleset;
