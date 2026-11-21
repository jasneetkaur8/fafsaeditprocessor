/**
 * <b>Step 2: input as generic JSON.</b>
 *
 * <p>Turns the incoming request body (a Jackson {@code JsonNode}) into plain Java values
 * ({@code Map}, {@code List}, {@code String}, {@code Long}, {@code Double}, {@code Boolean}) that CEL can read.
 * Because nothing here names specific FAFSA fields, a new input field needs no code change.
 *
 * <p>To build:
 * <ul>
 *   <li>{@code ApplicationData}: wraps the converted application and can look up a dot path
 *       such as {@code "income.parentIncome"} (used by {@code requires} and message templates)</li>
 *   <li>{@code JsonToCelConverter}: JsonNode to Map/List/primitive values</li>
 * </ul>
 */
package com.fafsa.editprocessor.input;
