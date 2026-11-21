# Design Decisions

How the FAFSA Edit Processor is built, why, and what was assumed. Build and usage instructions are
in [README.md](README.md).

## 1. Technology

| Choice | Why |
|---|---|
| Java 17, Spring Boot 3.5 | Matches the team's Java/Spring stack; Spring gives HTTP, Bean Validation, health and metrics with little code |
| [cel-java](https://github.com/google/cel-java) 0.14 | Rule expressions (section 2) |
| Jackson (JSON and YAML) | Strict request binding; reading the rule file |
| Bean Validation | Required fields and simple limits as annotations on the input record |
| Micrometer (Spring Actuator) | Metrics, with no extra infrastructure |

The input is a **typed Java record** (`FafsaApplication`), not a generic JSON map. Binding is
strict: unknown fields, wrong types (`"4"` for a number, `123` for text), and quietly "repaired"
values are rejected rather than guessed at, so a rule never runs on data the caller did not mean
to send. The one deliberate exception: income amounts are cut to 2 decimal places.

## 2. Rule representation: CEL expressions in YAML

Rules are **configuration, not code**: one YAML file (`src/main/resources/rules/fafsa-2026-27.yaml`,
or any file named by `FAFSA_RULES_LOCATION`). Each rule is data plus a small expression in
[CEL](https://cel.dev), Google's Common Expression Language:

```yaml
- id: HOUSEHOLD_COLLEGE_COUNT
  severity: ERROR
  requires: [household.numberInHousehold, household.numberInCollege]
  expect: household.numberInCollege <= household.numberInHousehold
  message: "Number in college (${household.numberInCollege}) cannot exceed number in household (${household.numberInHousehold})"
  tests:
    - input: { household: { numberInHousehold: 2, numberInCollege: 5 } }
      expect: FAILED
```

Why this over the alternatives:

| Option | Why not |
|---|---|
| Java code per rule | Every rule change is a code change, review and deploy |
| Database table | Needs an admin UI and migrations; still needs an expression language for the logic |
| Custom operators in YAML (`field: x, op: lte, value: y`) | Fine for one comparison, awkward for `&&`, `in` lists, optional fields; we would be inventing and maintaining a language |
| SpEL / MVEL / Groovy | Can call arbitrary Java: a rule file becomes a code-execution risk |
| Drools / DMN | Powerful but heavy for 7 field checks; hard for non-developers to read |
| JsonLogic | Safe, but rules as nested JSON are hard to read and write |

CEL is **safe by design** (no I/O, no Java calls, always terminates), **type-checked when the
file loads**, fast (compiled once), and reads like the rule it expresses. The limit: a capability
CEL lacks needs a small helper function in code (`ageInYears`, `isBlank` in `functions/RuleFunctions`).

## 3. Rule changes and activation

**Today**: rules change with no code change, but with a restart. Edit the YAML (or point
`FAFSA_RULES_LOCATION` at your own copy) and restart. On load the file is parsed, every expression
is compiled and type-checked, and every rule's embedded tests run; if anything fails the service
refuses to start, so a broken rule set can never go live. `/actuator/info` shows which version is
active.

The active rule set already sits behind an `AtomicReference`, and each request (or whole batch)
takes one snapshot, so swapping rules at runtime would never mix old and new rules in one result.

### Planned ahead: changing rules without a restart

The goal is that a rule author can check a new rule set, put it live, and undo it, all without a
restart or a deployment, and with the same guarantee as today: a rule set that fails any check
never goes live. The design builds on what exists (the compiler, the embedded tests, the
`AtomicReference` snapshot), so most of the work is an admin API, storage and safety around them.

**Phase 1: runtime activation on one instance**

1. **Admin API** (new `api/RuleAdminController`, under `/admin/ruleset`):

   | Endpoint | Does |
   |---|---|
   | `POST /admin/ruleset/validate` | Dry run: compiles the posted YAML, runs its tests and the replay (step 3); changes nothing |
   | `PUT /admin/ruleset` | Everything the dry run does, then activates |
   | `GET /admin/ruleset` | The active version and its YAML |
   | `GET /admin/ruleset/versions` | History: version, who activated it, when |
   | `POST /admin/ruleset/rollback/{version}` | Re-activates a stored version (checked again before it goes live) |

   A rejected rule set returns 422 with every problem, in the same `rule id: problem` form the
   compiler produces today.
2. **`RuleSetStore.activate(yaml, user, expectedVersion)`**: compile and run embedded tests with the
   existing `RuleSetCompiler`; require a `rulesetVersion` not used before (reusing one with
   different content is rejected); save; then swap the `AtomicReference`. Requests already running
   finish on the old rules, new requests get the new ones. All or nothing: on any failure the old
   rules stay active and nothing is saved.
3. **Replay before activation**: run a stored set of known applications (starting with `examples/`,
   later anonymised real ones) through the old and the new rules and return the differences, for
   example "3 applications now fail STATE_CODE", so the author sees the effect before going live.
4. **Storage** (new `store/RuleSetRepository` interface, first implementation on files): each
   version saved under a `fafsa.rules.storage-dir` setting, written to a temporary file and
   renamed so a crash never leaves half a file, plus an "active" pointer and the audit entries.
   On startup the store loads the active version from storage, and falls back to
   `fafsa.rules.location` when storage is empty.
5. **Concurrency**: one activation at a time. `PUT` names the version it was based on
   (`If-Match`); if someone else activated a version in between, it gets 409 instead of silently
   overwriting their change.
6. **Traceability**: the active version in an `X-Ruleset-Version` response header and in the
   per-request log line, so every result can be traced to the rules that produced it; a
   `fafsa.ruleset.activations{result}` counter and an audit log line for each activation and
   rollback.

**Phase 2: secure the admin API**

- Spring Security on `/admin/**` only: authenticated users with a rule-admin role (an API key to
  start, the organisation's SSO later); the validation API is unaffected.
- Serve admin endpoints on the management port or an internal network, not publicly.
- Limit the size of an uploaded rule file. CEL already guarantees a rule cannot run code; also cap
  expression length and loop iterations through CEL options.
- Optional, if the business requires it: a second person approves a version before it can be
  activated (drafts and approvals stored separately).

**Phase 3: several instances**

- Move the repository to shared storage (a database table or object store) with one "active"
  pointer.
- Each instance polls the pointer (for example every 30 seconds) and swaps when it changes; a
  message bus can push the change instead when instances must switch within seconds.
- `/actuator/info` already shows each instance's active version, which makes a stuck instance easy
  to spot.

**Tests to add**

- A rejected rule set leaves the old rules active and stores nothing.
- Activation under load: every response matches exactly one version, never a mix.
- Rollback, and a rejected rollback of a version that no longer passes the checks.
- The active version survives a restart.
- `If-Match` conflict returns 409; unauthenticated or non-admin calls are refused.

**Rejected alternative**: watching the rule file and reloading on change. It is the smallest
change, but the author gets no dry run or feedback beyond logs, there is no audit or rollback, and
with several instances each needs its own copy of the file.

## 4. Rule priority and order

There are two fixed stages, then the rules:

1. **Input checks** (`ApplicationValidator`): types, required fields, at least one income. If
   anything fails, the rules do not run (400 `INVALID_INPUT`).
2. **Defaults** (dependency status, marital status, household counts) are filled in, so rules see
   the completed application.
3. **Rules** run in the order they appear in the file.

Between rules, **order does not change the outcome**. Each rule only reads the application; no
rule changes data, depends on another rule's result, or stops the others. File order only decides
the order of `passed` and `violations` in the response. Severity does not reorder anything either; it only
decides the overall status (section 6). There is deliberately no priority field: with independent
rules it would add configuration without changing any result.

## 5. Error handling: collect all errors

**Collect everything, never stop at the first problem**, at both stages:

- **Input**: each field is bound on its own (`ApplicationValidator.toApplication`), so a bad enum
  value does not hide a missing student name. Bad values, unknown fields and missing required fields
  come back together in one 400, with the defaults that would apply as `warnings`. A field with a bad
  value is not also reported as missing. Only a body that is not a JSON object at all (broken JSON,
  an array, duplicate keys) is reported on its own, since no field can be read.
- **Rules**: every rule runs; all failures are listed in `violations`.
- **A rule that cannot run** (for example comparing text with a number) is `ERRORED`: it is
  isolated, logged with the rule id, the other rules still run, and the application is reported
  `INVALID`, never `VALID`, so a broken rule cannot let an application through silently.
- **A broken rule file never goes live**: the compiler lists every problem in the file (unknown
  keys, expressions that do not compile, unknown fields, undefined lists, failing embedded tests)
  and the application refuses to start.

## 6. Severity levels and overall status

Each rule declares a severity:

| Severity | Meaning | Effect on status |
|---|---|---|
| `ERROR` | The application cannot be accepted as is | Any failure → `INVALID` |
| `WARNING` | Suspicious, should be reviewed | Failures only → `VALID_WITH_WARNINGS` |
| `INFO` | Worth noting | Listed in `violations`, status unchanged |

Each rule run has an outcome: `PASSED`, `FAILED`, `NOT_APPLICABLE` (a `requires` field is missing,
or `when` is false) or `ERRORED` (could not be evaluated; always makes the application `INVALID`).

The response `status` combines both stages:

| Status | HTTP | When |
|---|---|---|
| `INVALID_INPUT` | 400 | Input checks failed; rules did not run; `errors` lists every problem |
| `INVALID` | 200 | An `ERROR` rule failed, or a rule errored |
| `VALID_WITH_WARNINGS` | 200 | Only `WARNING`/`INFO` rules failed, and/or defaults were applied (`warnings`) |
| `VALID` | 200 | Every rule passed or did not apply, nothing defaulted |

All 7 current rules are `ERROR`. Changing a rule's severity is a one-word edit in the YAML.

## 7. Rule conflicts

Rules **cannot override each other**: every rule is a condition the application must meet, rules
do not change data, and there is no "rule A wins over rule B". So the only possible conflict is a
**contradiction**: two rules that no application can pass together (for example one requiring
`numberInCollege <= numberInHousehold` and another requiring the opposite).

That is not detected automatically (proving two expressions can never both be true is a hard
problem in general). It is caught in practice by:

- **A whole-rule-set test**: `examples/valid-application.json` must pass every rule
  (`FafsaEditResourceTest.validApplicationReturnsValid`). A contradiction makes that impossible, so
  the test fails.
- **Embedded tests** per rule, run every time the file loads, showing what each rule accepts.
- **Unique ids**: two rules with the same id are rejected, so one cannot silently replace another.

Also worth knowing: **defaults interact with rules**. Rules see defaulted values, so
`numberInCollege: 3` with no `numberInHousehold` fails `HOUSEHOLD_COLLEGE_COUNT` (household
defaults to 1). That is intended: the warning says what was assumed and the violation says why it
does not fit (tested in `defaultedValuesAreWhatTheRulesSee`).

## 8. Performance

Expensive work happens **once, when the rule file loads**: parsing, type-checking and compiling
every CEL expression into a reusable program, and running the embedded tests (about 0.5 s for the
current file). Per application, the engine only converts the application to CEL data once and runs
the precompiled programs.

Measured on a laptop (engine only, no HTTP, 7 rules, after warm-up):

| | |
|---|---|
| One application | about 10 µs |
| One thread | about 95,000 applications/s |
| 8 cores in parallel | about 410,000 applications/s |

Why it scales to many applications:

- **Compiled rule set is immutable and thread-safe**, shared by all requests with no locking; each
  request takes one snapshot (`RuleSetStore.active()`), so a future runtime reload can never mix
  old and new rules mid-request.
- **Stateless**: no per-application state is kept, so more instances scale linearly.
- **Cheap skips**: `requires` is checked before any expression runs; missing fields make a rule
  `NOT_APPLICABLE` without evaluating it.
- **Cost is linear** in rules × applications, with a small constant.

- **Batch endpoint** (`POST /api/v1/applications/validate/batch`, up to
  `fafsa.validation.max-batch-size` applications) cuts per-request HTTP overhead for bulk loads;
  the whole batch shares one rule-set snapshot, and items run one after another (1000 items take
  about 10 ms of engine time).

- **Parallel rules within one application**, for large rule sets. Once a rule set has at least
  `fafsa.engine.parallel.min-rules` rules (default 50), one application's rules run concurrently on
  a dedicated pool (`fafsa.engine.parallel.threads`, default one per core). Results keep rule order,
  a failing rule is still isolated, and the request id follows onto the pool threads. Rules only
  read the application, so this needs no locking. Measured, 8 cores (sequential → parallel):

  | Rules | Sequential | Parallel | Speedup |
  |---|---|---|---|
  | 7 (current file) | 11.8 µs | 19.6 µs | 0.6x: slower |
  | 50 | 114 µs | 77 µs | 1.5x |
  | 200 | 425 µs | 210 µs | 2.0x |
  | 1000 | 2.4 ms | 1.1 ms | 2.2x |

  Below about 15–30 rules, handing rules to other threads costs more than it saves, so the
  current 7 rules run one after another. Parallelism here **lowers one application's latency**; it
  does not raise total throughput when many requests already keep every core busy.

If profiling showed the conversion to CEL data dominating, it could be replaced by a hand-written
mapping.

## Extensibility: adding rules

| Change | What it takes |
|---|---|
| New rule over existing fields | Add a YAML entry with `tests`, restart. **No code change** |
| Change a rule's logic, message or severity | Edit the YAML, restart |
| New list of allowed values | Add it under `lists:` and use `lists.<name>` |
| New input field | Add it to the `FafsaApplication` record (typed input is deliberate); rules can use it straight away, with no change to the engine or compiler |
| A capability CEL lacks | One small generic function in `functions/RuleFunctions`, documented in `RULE_AUTHORING.md` |

Safety nets make editing low-risk for non-developers: unknown YAML keys, unknown field paths,
undefined lists, expressions that do not compile or do not give true/false, and failing embedded
tests all stop the file from loading, with a message naming the rule. `RULE_AUTHORING.md` is the
guide. Today a change needs a restart; runtime activation and rollback (section 3) is the planned
next step.

## 9. Security

- **Rules cannot run code**: CEL has no I/O and no access to Java, and always terminates, so a rule
  file cannot do anything except compute true or false.
- **Strict input**: unknown fields and wrong types are rejected (section 1); batches are capped at
  `fafsa.validation.max-batch-size`.
- **No personal data in logs or metrics**: logs hold statuses, rule ids and field names only;
  parser error messages, which can quote the request body, are not logged. Field names a caller
  invents are logged and tagged as `unknown`, so they cannot inject text into logs or create
  unlimited metrics. A caller's `X-Request-Id` is reused only if it is a plain token.
- **Error responses do not reveal internals**: no stack traces, request paths, or parser details.
- **Planned ahead**: authentication (the validation API is open today) and the admin API for
  changing rules, secured with an admin role, an audit log and a size limit on uploaded rule files
  (section 3, phase 2).

## 10. Production considerations

In place: health, info (active rule set version) and metrics endpoints; a request id on every log
line; external configuration (rule file location, batch size, parallelism); the service is
stateless, so it scales by adding instances; a bad rule file fails at startup rather than at
request time.

Would add before production: authentication and rate limiting; the admin API with audit
(section 3); the rule set version in each response (for example an `X-Ruleset-Version` header) so
every result can be traced to the rules that produced it; dashboards and alerts on
`fafsa.rule.results{outcome="ERRORED"}` and on error rates; a container image and deployment
configuration.

## 11. Assumptions

- **Age** is computed in whole years from the date of birth to the server's current date. There is
  no upper age limit (the assignment's background mentions 120; only "at least 14" is required).
- **SSN** is exactly 9 digits with no dashes or spaces. It is never echoed in messages.
- **Dependency and marital status** take exactly `dependent`/`independent` and `single`/`married`
  (case-sensitive). When missing they default to `independent` and `single`, reported as warnings,
  rather than failing the application.
- **Household counts** default to 1 when missing (the student) and must be above 0.
- **Income**: at least one of student or parent income must be given. Amounts keep 2 decimal
  places; further digits are dropped, not rounded. Negative amounts pass the input checks so that
  the `NON_NEGATIVE_INCOME` rule reports them with its message. Zero parent income counts as given.
- **State** must be an upper-case postal abbreviation: the 50 states, DC, and the territories
  AS, GU, MP, PR, VI.
- **Spouse information** means first name, last name and SSN, none blank. The spouse SSN format is
  not checked; no rule asked for it.
- **Results**: an application that breaks rules is still HTTP 200 (it was processed); only input
  that cannot be checked is a 400.

## 12. Testing strategy

Tests are layered so a failure points at its cause (94 tests, `./mvnw clean verify`):

| Layer | What it proves | Where |
|---|---|---|
| Embedded rule tests | Each of the 7 rules passes, fails and does not apply as intended (29 examples, run on every load) | `fafsa-2026-27.yaml` |
| Compiler | Broken rule files are rejected with every problem listed; the real file compiles | `RuleSetCompilerTest` (10) |
| Engine | Status from severities, `ERRORED` isolation, parallel = sequential results and order, metrics | `RuleEngineTest` (7) |
| Input checks | Required fields, defaults, all problems reported together, income truncation | `ApplicationValidatorTest` (10) |
| API end to end | The real rule file through HTTP: both sample applications, 20+ malformed-input cases, batch, response shape, logs without personal data, metrics | `FafsaEditResourceTest` (66) |

The end-to-end tests use a fixed clock so the age rule gives the same answer on any day.

## 13. What I'd do next

1. Runtime rule activation through the admin API (section 3).
2. The rule set version in every response, for traceability.
3. A coverage report (JaCoCo) in the build, and tests that replay a larger set of anonymised real
   applications against each new rule set.
4. Rule-level options the domain will likely need: effective dates per rule (rules that change
   during the year), and rules that check the spouse SSN format or an upper age limit.

## Use of AI

I built this with Claude Code as a pair programmer. I made the design decisions and reviewed and
steered the output; the AI wrote most of the code and tests, ran the build, and measured
performance. Places where I changed its direction:

- Input binds to typed records with strict JSON, instead of the generic JSON map
- Fixed value sets (`dependencyStatus`, `maritalStatus`) are enums.
- Every input problem is reported in one response, including missing fields and bad values
  together, while still deserializing to the typed record.
- The exception handler was simplified; error responses was simplified with extra and useless information removed.
- I rejected a custom validation annotation in favour of a plain check in the validator for simplicity and maintainability.
- Responses carry only what applies (`passed`, `violations`, `warnings` only when non-empty).
- Logs and metrics were added before any further features; parallel rule evaluation was added and
  then measured, which set its default threshold.

## Time spent

About 2 hours 45 minutes. Much of the time went into setting up the development environment, and
the work was spread out over the evening.
