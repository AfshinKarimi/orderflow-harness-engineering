# Harness benchmark

Design, protocol and metrics: [docs/benchmark/benchmark-design.md](../docs/benchmark/benchmark-design.md).

| Path | Visible to the agent? | Content |
|---|---|---|
| `tasks/Txx-*/prompt.md` | yes: sent verbatim as turn 1 | requirements and acceptance criteria |
| `tasks/Txx-*/spec.md` | **no** | oracle tests, architecture traps, reference solution outline |
| `arms/A/` | no (used to build arm A) | minimal `CLAUDE.md` and `README.md`, removed paths, `pom.xml` changes |
| `oracle/common/`, `oracle/Txx-*/` | **no** | hidden tests: architecture, schema ownership, migrations, per-task acceptance and guard tests |
| `reference/Txx-*.patch` | **no** | reference solution per task (patch against `69c9d98`) |
| `oracle/validation/` | **no** | per-test results of `scripts/validate-oracle.sh` |
| `scripts/` | **no** | `prepare-oracle.sh`, `validate-oracle.sh`, `oracle_check.py` |

This directory exists only on the benchmark branch. Runs start from a history-free clone of `bench/base-A` or
`bench/base-B`, which do not contain it.

Status: increment 2 in progress. Oracle and reference solution done and validated for T01-T06; T07-T10
pending. The runner (increment 3) is not written. No benchmark has been run.

Validate a task's oracle (needs Docker and a filled Maven cache): `benchmark/scripts/validate-oracle.sh T03`
