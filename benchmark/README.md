# Harness benchmark

Design, protocol and metrics: [docs/benchmark/benchmark-design.md](../docs/benchmark/benchmark-design.md).

| Path | Visible to the agent? | Content |
|---|---|---|
| `tasks/Txx-*/prompt.md` | yes: sent verbatim as turn 1 | requirements and acceptance criteria |
| `tasks/Txx-*/spec.md` | **no** | oracle tests, architecture traps, reference solution outline |
| `arms/A/` | no (used to build arm A) | minimal `CLAUDE.md` and `README.md`, removed paths, `pom.xml` changes |

This directory exists only on the benchmark branch. Runs start from a history-free clone of `bench/base-A` or
`bench/base-B`, which do not contain it.

Status: increment 1 (design and tasks). Oracle tests, reference solutions and the runner are not written yet.
No benchmark has been run.
