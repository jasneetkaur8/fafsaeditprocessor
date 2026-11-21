# FAFSA Edit Processor

Applies FAFSA edit (validation) rules to application data. Rules are written in YAML using
[CEL (Common Expression Language)](https://cel.dev), and can be changed at runtime with no code change.

- Design rationale: [DECISIONS.md](DECISIONS.md)
- Writing rules: [RULE_AUTHORING.md](RULE_AUTHORING.md)

## Prerequisites

- JDK 17+

## Build, test, run

```bash
./mvnw clean test
```

```bash
./mvnw spring-boot:run
```

Windows: use `mvnw.cmd`. Health check: http://localhost:8080/actuator/health

## Build progress

- [x] 1. Scaffold
- [ ] 2. Input as generic JSON (`input`)
- [ ] 3. Rule-set format (`ruleset`)
- [ ] 4. Compiler and validator (`compiler`)
- [ ] 5. Helper functions (`functions`)
- [ ] 6. Engine (`engine`)
- [ ] 7. The 7 rules in YAML (`resources/rules/fafsa-2026-27.yaml`)
- [ ] 8. Rule store and runtime activation (`store`)
- [ ] 9. Validation API (`api`)
- [ ] 10. Rule admin API (`api`)
- [ ] 11. Logging and metrics
- [ ] 12. Documentation
- [ ] 13. Final check and push

## API

_TODO (Steps 9–10): endpoints table and curl examples._

## Project layout

```
src/main/java/com/fafsa/editprocessor/
├── input/      Step 2:  request JSON -> CEL-readable data
├── ruleset/    Step 3:  YAML rule-set model
├── compiler/   Step 4:  compile and validate rule sets
├── functions/  Step 5:  CEL helper functions (ageInYears, ...)
├── engine/     Step 6:  evaluate rules, build results
├── store/      Step 8:  active rule set, versions, runtime activation
├── api/        Steps 9-10: REST controllers
└── config/     Spring configuration
src/main/resources/rules/   Rule sets (YAML)
examples/                   Sample request payloads
requests.http               IntelliJ HTTP client requests
```
