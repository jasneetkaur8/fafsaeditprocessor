# Writing FAFSA Edit Rules

Rules live in a YAML file, `src/main/resources/rules/fafsa-2026-27.yaml` by default. Changing,
adding or removing a rule needs **no code change**: edit the file and restart. To use a file
outside the build, point the app at it:

```bash
FAFSA_RULES_LOCATION=file:/etc/fafsa/rules.yaml java -jar fafsaeditprocessor.jar
```

The file is checked when it loads. If anything is wrong — a typo in a key, an expression that does
not compile, an unknown field, a failing test — the app **refuses to start** and lists every
problem, so a broken rule never goes live.

## Rule format

```yaml
rulesetVersion: "2026-27.1"      # reported with every result; change it when the rules change

lists:                           # named lists that rules use as lists.<name>
  usStates: [AL, AK, CA, NY]

rules:
  - id: HOUSEHOLD_COLLEGE_COUNT                  # unique
    severity: ERROR                              # ERROR, WARNING or INFO
    description: Number in college cannot exceed number in household
    requires: [household.numberInHousehold, household.numberInCollege]   # optional
    when: maritalStatus == "married"             # optional
    expect: household.numberInCollege <= household.numberInHousehold
    field: household.numberInCollege             # optional: the field reported as wrong
    message: "Number in college (${household.numberInCollege}) cannot exceed number in household (${household.numberInHousehold})"
    remediation: Correct either count            # optional
    tests:                                       # at least one
      - name: too many in college                # optional
        input: { household: { numberInHousehold: 2, numberInCollege: 5 } }
        expect: FAILED
```

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Unique name, shown in results |
| `severity` | yes | `ERROR` makes the application INVALID; `WARNING` gives VALID_WITH_WARNINGS; `INFO` is reported only |
| `description` | no | What the rule checks |
| `requires` | no | Fields that must be present; if one is missing the rule does not apply |
| `when` | no | Condition for the rule to apply |
| `expect` | yes | Condition the application must meet |
| `field` | no | Field reported as wrong |
| `message` | yes | Shown when the rule fails. `${path}` is replaced by that field's value (`not provided` if absent) |
| `remediation` | no | How to fix it |
| `tests` | yes | Examples checked at load time |

Rules run in file order, and every rule runs — one failure never hides another.

## How a rule is evaluated

1. **`requires`**: if any listed field is missing → `NOT_APPLICABLE`
2. **`when`**: if false → `NOT_APPLICABLE`
3. **`expect`**: true → `PASSED`, false → `FAILED`

If a rule cannot be evaluated at all (for example, comparing text with a number) it is `ERRORED`,
and the application is reported INVALID rather than passing silently.

Before rules run, required information (student info, income, state) has been checked and these
defaults applied: `dependencyStatus` → `independent`, `maritalStatus` → `single`, household counts
→ 1. Rules see the defaulted values.

## Fields

Write fields as the request JSON names them:

| Field | Type |
|---|---|
| `studentInfo.firstName`, `studentInfo.lastName`, `studentInfo.ssn` | text |
| `studentInfo.dateOfBirth` | text, `yyyy-MM-dd` |
| `dependencyStatus`, `maritalStatus` | text (`dependent`/`independent`, `single`/`married`) |
| `spouseInfo.firstName`, `spouseInfo.lastName`, `spouseInfo.ssn` | text |
| `household.numberInHousehold`, `household.numberInCollege` | whole number |
| `income.studentIncome`, `income.parentIncome` | number |
| `stateOfResidence` | text |

A field that was not provided is absent. Test for it with `has(...)` (for example
`has(income.parentIncome)`), or list it under `requires`. A path that is not one of these fields
is rejected at load time.

## CEL cheat sheet

Expressions use [CEL](https://cel.dev).

| Want | Write |
|---|---|
| Compare | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| And / or / not | `&&`, `\|\|`, `!` |
| Field provided | `has(income.parentIncome)` |
| In a list | `stateOfResidence in lists.usStates` or `maritalStatus in ["single", "married"]` |
| Pattern | `studentInfo.ssn.matches('^[0-9]{9}$')` |
| Text helpers | `size(s)`, `s.startsWith("A")`, `s.contains("x")`, `s.lowerAscii()`, `s.trim()` |
| If / else | `cond ? a : b` |

Text comparisons are exact: `"Married"` is not `"married"`. Use `.lowerAscii()` to ignore case.
Long expressions can span lines with `>-`:

```yaml
    expect: >-
      (!has(income.studentIncome) || income.studentIncome >= 0) &&
      (!has(income.parentIncome) || income.parentIncome >= 0)
```

## Helper functions

| Function | Result | Example |
|---|---|---|
| `ageInYears(date)` | whole years from a `yyyy-MM-dd` date to today (negative if in the future) | `ageInYears(studentInfo.dateOfBirth) >= 14` |
| `isBlank(text)` | true if empty or only spaces | `!isBlank(spouseInfo.ssn)` |
| `today` | today's date as `yyyy-MM-dd` | `studentInfo.dateOfBirth < today` |

## Named lists

Define a list once under `lists:` and use it as `lists.<name>`. Using a list that is not defined
is rejected at load time.

## Testing a rule

Every rule needs `tests`. Each test is a partial application (same shape as the request JSON) and
the outcome the rule must give: `PASSED`, `FAILED`, `NOT_APPLICABLE` or `ERRORED`. All tests run
when the file loads; any mismatch stops the app with a message such as:

```
STATE_CODE: test 2 (territory) expected PASSED but got FAILED
```

Tips:
- Cover a pass, a fail, and the case where the rule does not apply.
- Quote values that look like numbers but are text: `ssn: "123456789"`.
- Avoid tests that depend on today's date crossing a boundary; use clearly past or future dates.
