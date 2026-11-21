/**
 * <b>Steps 9 and 10: REST API.</b>
 *
 * <p>To build:
 * <ul>
 *   <li>{@code ValidationController}: POST /api/v1/applications/validate,
 *       POST /api/v1/applications/validate/batch, GET /api/v1/rules</li>
 *   <li>{@code RuleAdminController}: POST /admin/ruleset/validate (dry run), PUT /admin/ruleset
 *       (validate and activate), GET /admin/ruleset/versions, POST /admin/ruleset/rollback/{version}</li>
 *   <li>{@code ApiExceptionHandler}: RFC 7807 problem responses (malformed JSON, rejected rule sets)</li>
 * </ul>
 */
package com.fafsa.editprocessor.api;
