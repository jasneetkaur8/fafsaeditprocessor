# Design Decisions

_Fill in as you build (Step 0 notes go here first)._

## 1. Technology
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

## 3. Runtime activation
<!-- validate -> compile -> run embedded tests -> atomic swap; per-request snapshot; rollback -->
## 4. Rule priority and order

There are two fixed stages, then the rules:

1. **Input checks** (`ApplicationValidator`): types, required fields, at least one income. If
   anything fails, the rules do not run (400 `INVALID_INPUT`).
2. **Defaults** (dependency status, marital status, household counts) are filled in, so rules see
   the completed application.
3. **Rules** run in the order they appear in the file.

Between rules, **order does not change the outcome**. Each rule only reads the application; no
rule changes data, depends on another rule's result, or stops the others. File order only decides
the order of `violations` in the response. Severity does not reorder anything either; it only
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

Not built: running rules in parallel within one application, which is not worth it at 10 µs. If
profiling showed the conversion to CEL data dominating, it could be replaced by a hand-written
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

## 9. Security of the admin API
## 10. Production considerations
## 11. Assumptions
## 12. Testing strategy
## 13. What I'd do next
## Use of AI
## Time spent
