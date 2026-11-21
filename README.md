# FAFSA Edit Processor

A Spring Boot service that applies FAFSA edit (validation) rules to application data and returns
which rules passed, which failed, why, and how to fix them.

Rules live in a YAML file and are written in [CEL (Common Expression Language)](https://cel.dev).
Adding or changing a rule needs **no code change**: edit the file and restart.

- Design decisions and trade-offs: [DECISIONS.md](DECISIONS.md)
- Writing and changing rules: [RULE_AUTHORING.md](RULE_AUTHORING.md)

## Prerequisites

- JDK 17 or newer. Maven is not needed; the Maven wrapper (`./mvnw`) downloads it.

## Build, test, run

```bash
./mvnw clean verify          # build and run all tests
./mvnw spring-boot:run       # start on http://localhost:8080
```

Or run the built jar:

```bash
java -jar target/fafsaeditprocessor-0.1.0-SNAPSHOT.jar
```

Windows: use `mvnw.cmd`. Health check: `curl localhost:8080/actuator/health`.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/applications/validate` | Validate one application |
| `POST` | `/api/v1/applications/validate/batch` | Validate a JSON array of up to 1000 applications |
| `GET` | `/actuator/health` | Health |
| `GET` | `/actuator/info` | Active rule set version, rule count, load time |
| `GET` | `/actuator/metrics` | Metrics (see [Observability](#observability)) |

`requests.http` has ready-made requests for the IntelliJ HTTP client.

### Validate one application

```bash
curl -s -X POST localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' -d @examples/valid-application.json
```
```json
{"status": "VALID", "message": "Valid application",
 "passed": ["STUDENT_AGE", "SSN_FORMAT", "DEPENDENT_PARENT_INCOME", "NON_NEGATIVE_INCOME",
            "HOUSEHOLD_COLLEGE_COUNT", "STATE_CODE"]}
```

A rule that does not apply (here spouse info, for a single student) is in neither list.

### An application that breaks rules

```bash
curl -s -X POST localhost:8080/api/v1/applications/validate \
  -H 'Content-Type: application/json' -d @examples/invalid-application.json
```
```json
{"status": "INVALID", "message": "Application failed 7 rules",
 "violations": [
   {"ruleId": "STUDENT_AGE", "severity": "ERROR", "outcome": "FAILED",
    "field": "studentInfo.dateOfBirth",
    "message": "Student must be at least 14 years old (date of birth: 2015-01-01)",
    "remediation": "Check the student's date of birth"},
   {"ruleId": "HOUSEHOLD_COLLEGE_COUNT", "severity": "ERROR", "outcome": "FAILED",
    "field": "household.numberInCollege",
    "message": "Number in college (5) cannot exceed number in household (2)",
    "remediation": "Correct either count; people in college are also counted in the household"}
   ...]}
```

A rule-check result is HTTP 200 even when the application is `INVALID`: the application was
processed.

### Input that cannot be checked (HTTP 400)

Missing required information, values of the wrong type and unknown fields are all reported
together, before any rule runs, along with the defaults that would apply:

```bash
curl -s -X POST localhost:8080/api/v1/applications/validate -H 'Content-Type: application/json' \
  -d '{"dependencyStatus": "abc", "income": {}, "stateOfResidence": "CA"}'
```
```json
{"status": "INVALID_INPUT", "message": "3 problems with the application",
 "errors": [
   {"field": "dependencyStatus", "problem": "must be one of: dependent, independent"},
   {"field": "income", "problem": "must have studentIncome or parentIncome"},
   {"field": "studentInfo", "problem": "must not be null"}],
 "warnings": [
   {"field": "maritalStatus", "value": "single", "message": "Marital status not provided; defaulted to single"},
   ...]}
```

### Validate a batch

```bash
curl -s -X POST localhost:8080/api/v1/applications/validate/batch -H 'Content-Type: application/json' \
  -d "[$(cat examples/valid-application.json), $(cat examples/invalid-application.json)]"
```
```json
{"results": [
  {"index": 0, "status": "VALID", "message": "Valid application", "passed": [...]},
  {"index": 1, "status": "INVALID", "message": "Application failed 7 rules", "violations": [...]}]}
```

Each application is checked independently; an invalid one does not fail the batch. The batch is
a 400 only when the body is not a usable array (broken JSON, not an array, empty, or too large).

### Response fields

| Field | When present |
|---|---|
| `status` | Always: `VALID`, `VALID_WITH_WARNINGS`, `INVALID`, or `INVALID_INPUT` |
| `message` | Always: a one-line summary |
| `errors` | `INVALID_INPUT` only: `field` and `problem` for each input problem |
| `warnings` | When defaults were applied: `field`, `value`, `message` |
| `passed` | Ids of rules the application passed |
| `violations` | Failed rules: `ruleId`, `severity`, `outcome`, `field`, `message`, `remediation` |

Empty lists are left out.

### Input rules

- `studentInfo` (all four fields), `income` (with at least one of `studentIncome` or
  `parentIncome`) and `stateOfResidence` are required.
- `dependencyStatus` is `dependent` or `independent` (default `independent`); `maritalStatus` is
  `single` or `married` (default `single`). Household counts are whole numbers above 0 (default 1).
- Dates are `yyyy-MM-dd`. Income amounts keep 2 decimal places; further digits are dropped.
- Unknown fields and values of the wrong type (`"4"` for a number, `123` for text) are rejected.

## The rules

All seven are in `src/main/resources/rules/fafsa-2026-27.yaml`, each with its own examples that
run every time the file loads:

| Id | Checks |
|---|---|
| `STUDENT_AGE` | Student is at least 14 |
| `SSN_FORMAT` | SSN is exactly 9 digits |
| `DEPENDENT_PARENT_INCOME` | Parent income is given when the student is dependent |
| `NON_NEGATIVE_INCOME` | No income is negative |
| `HOUSEHOLD_COLLEGE_COUNT` | Number in college does not exceed number in household |
| `STATE_CODE` | State is a valid US state or territory abbreviation |
| `MARRIED_SPOUSE_INFO` | Spouse name and SSN are given when the student is married |

### Changing rules

1. Copy the rule file somewhere outside the build and edit it (see [RULE_AUTHORING.md](RULE_AUTHORING.md)).
   Change `rulesetVersion`, and give each new rule `tests`.
2. Start or restart the service with the file:
   ```bash
   FAFSA_RULES_LOCATION=file:/etc/fafsa/rules.yaml java -jar target/fafsaeditprocessor-0.1.0-SNAPSHOT.jar
   ```
3. Check `curl localhost:8080/actuator/info` shows the new version.

The file is checked on load. If anything is wrong (a typo in a key, an expression that does not
compile, an unknown field, a failing test) the service refuses to start and lists every problem,
so a broken rule set never goes live. To try a file without touching the running service, start a
second copy with `--server.port=8099`.

## Configuration

Set in `src/main/resources/application.yml`; any of them can be overridden with an environment
variable (e.g. `FAFSA_RULES_LOCATION`) or a `--name=value` argument.

| Setting | Default | Meaning |
|---|---|---|
| `fafsa.rules.location` | `classpath:rules/fafsa-2026-27.yaml` | Rule file loaded at startup |
| `fafsa.validation.max-batch-size` | `1000` | Most applications in one batch |
| `fafsa.engine.parallel.min-rules` | `50` | Run an application's rules in parallel from this many rules (0 = never) |
| `fafsa.engine.parallel.threads` | `0` | Threads for parallel rules (0 = one per CPU core) |

## Observability

- **Logs**: one line per application and per batch, with status, failed rule ids and field names,
  never application data (no SSN, names, dates or amounts). Every line carries a request id,
  returned in the `X-Request-Id` header (a caller-supplied plain id is reused).
- **Metrics** (`/actuator/metrics`): `fafsa.validations` (by mode and status), `fafsa.rule.results`
  (by rule and outcome), `fafsa.rules.evaluation` (timer), `fafsa.input.errors` and
  `fafsa.defaults.applied` (by field), `fafsa.batch.size`, `fafsa.batch.rejected`,
  `fafsa.ruleset.rules`, plus Spring's `http.server.requests`.

## Project layout

```
src/main/java/com/fafsa/editprocessor/
├── api/        HTTP endpoints, request reading, responses, error handling, telemetry
├── input/      Application record, input checks and defaults, conversion to rule data
├── ruleset/    YAML rule file model and parser
├── compiler/   Checks and compiles a rule file; runs each rule's embedded tests
├── functions/  Helper functions for rules (ageInYears, isBlank)
├── engine/     Runs the rules against an application
├── store/      Holds the active rule set
└── config/     Spring configuration (strict JSON, rules settings, request id)
src/main/resources/rules/   Rule files (YAML)
src/test/java/              Tests
examples/                   Sample applications
requests.http               Requests for the IntelliJ HTTP client
```
