# Arm A: pom.xml changes

Applied to the base commit's `pom.xml` to build `bench/base-A`. Nothing else in the build changes.

| Remove | Why |
|---|---|
| property `archunit.version` and dependency `com.tngtech.archunit:archunit-junit5` | architecture guardrail |
| properties `jacoco-plugin.version`, plugin `org.jacoco:jacoco-maven-plugin` | coverage guardrail |
| properties `checkstyle-plugin.version`, `checkstyle-tool.version`, plugin `maven-checkstyle-plugin` | style guardrail |
| comments referring to `docs/` or the quality gate | point to removed files |

Kept: all application dependencies, Spring Boot, Failsafe (so `*IT` tests still run), Testcontainers.

Check after building the arm: `./mvnw -o verify` passes on `bench/base-A` with the remaining regression tests,
and `grep -ri -E "archunit|jacoco|checkstyle|quality-gate|docs/" pom.xml` finds nothing.
