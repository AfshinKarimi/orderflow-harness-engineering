---
name: architecture-review
description: Check an OrderFlow design, plan or code change against the modular-monolith and hexagonal architecture rules, including constraints ArchUnit cannot enforce. Use when a change adds or moves packages, adds dependencies between modules or layers, touches persistence, events or the shared kernel, or introduces a new module or library.
---

# Architecture review

## Purpose
Catch boundary violations and undocumented architectural decisions before they land.
The source of truth is `docs/architecture/architecture.md` (numbered rules) and `docs/adr/`.
This skill applies those rules; it does not redefine them.

## When to use
- Reviewing a plan before approval, or a diff before it is considered done.
- A new module, layer, package, library, integration (Kafka, external HTTP, AI service) or persistence change.
- An ArchUnit failure, to decide between fixing the code and changing the rule (the latter needs an explicit decision).

## Workflow
1. **Read the sources.** `architecture.md`, the ADRs, and the change (`git diff`, `git status` for new files, or the plan).
2. **Map the change.** List each new or changed class with its module and layer (`order.application.port.in`, etc.). Anything that doesn't fit a layer is a finding.
3. **Check dependencies.** For each class, check its imports against the numbered rules. Pay special attention to:
   - cross-module dependencies (only `port.in` / `application.event`, only from `adapter.in`);
   - framework types appearing in `domain` or `application`;
   - inbound adapters reaching past `port.in`.
4. **Run the enforced rules:** `./mvnw test -Dtest=ArchitectureTest` (JDK 21; see CLAUDE.md). Report actual counts and any violation text.
5. **Check what ArchUnit cannot see** (manually):
   - **Data ownership (rule 11):** migrations and queries touch only the module's own schema; no cross-module FKs, joins or native SQL.
   - **Logic placement:** business decisions (`if` on business state, calculations, invariants) live in `domain`, not in services, controllers or mappers.
   - **Public contracts:** changes to `port.in` or `application.event` types are backward compatible, or are flagged as breaking. No domain types leak through generics (e.g. `List<OrderLine>`).
   - **Runtime coupling:** no reflection, bean lookup by name, or string-typed event/topic names that bypass the boundaries.
   - **Transactions and events:** transaction boundary sits on the use case; event publication timing relative to commit is stated.
6. **Is a new rule needed?** If the change introduces a constraint that is architectural and checkable, propose an ArchUnit rule with its reason. Don't propose style rules.
7. **Is an ADR needed?** Yes for a new module, a new infrastructure dependency, a change to boundaries or to a communication style, or reversing a previous decision.

## Quality checklist
- [ ] Every changed class is in a well-defined module and layer.
- [ ] No rule in `architecture.md` is violated, including rule 11.
- [ ] `ArchitectureTest` was executed and its real result reported.
- [ ] No ArchUnit rule was relaxed, disabled or narrowed to make the change pass.
- [ ] Public contract changes are identified and classified (compatible / breaking).
- [ ] Business logic is in `domain`.
- [ ] New dependencies and decisions are justified, and an ADR is proposed where needed.
- [ ] `architecture.md` is updated if the rules changed.

## Expected output
1. **Verdict:** `OK` · `Changes needed` · `Decision needed`.
2. **Findings**, most severe first, each with: rule number (or "not enforced: <constraint>"), `file:line`, the problem, and a concrete fix.
3. **ArchUnit result:** the command and the actual counts.
4. **Proposed new rules or ADRs**, each with its reason. Write "none" if there are none.
5. **Decisions for the user**, each with options and a recommendation.
