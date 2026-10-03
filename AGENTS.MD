Here’s a compact root-level `AGENTS.md` optimized for agent behavior rather than explanation:

# AGENTS.md

## Mission

Preserve and improve the Android app without regressing existing work.

## Mandatory Rules

1. **Sync first**

   * Before any work, fetch and pull the latest upstream changes.
   * Inspect the resulting working tree and recent history.
   * Never assume the local checkout is current.

2. **Understand before modifying**

   * Before changing code, inspect relevant existing implementation, recent commits, diffs, TODOs, and adjacent code.
   * Identify and understand changes made by other contributors.
   * Preserve their intent and behavior unless the user explicitly requests otherwise.
   * If intent is unclear, ask the user before modifying it.

3. **Never regress others' work**

   * Do not overwrite, revert, remove, or substantially alter another contributor's changes merely to implement your own solution.
   * Prefer minimal, additive, localized changes.
   * Before finalizing, review the diff specifically for unintended changes or regressions.
   * If two changes conflict, stop and ask the user rather than choosing silently.

4. **Never build locally**

   * Do **not** run Gradle builds, APK/AAB generation, lint, or other build commands locally.
   * Do not install or modify local Android SDK/Gradle/JDK tooling to enable builds.
   * All building and build-dependent verification must happen through the user's Google AI Studio environment.

5. **Fundamental changes require an interview**

   * Before making any change that could fundamentally alter the application's architecture, UX, behavior, data model, navigation, API contracts, dependencies, security model, persistence, or user-facing functionality, interview the user first.
   * Ask focused questions about intended behavior, constraints, compatibility, and acceptance criteria.
   * Do not begin such modifications until the user's answers establish the intended direction.
   * Routine bug fixes, refactors with identical behavior, documentation changes, and narrowly scoped maintenance do not require an interview unless their impact is uncertain.

6. **Keep changes minimal**

   * Solve the requested problem with the smallest safe change.
   * Avoid unrelated refactoring, formatting churn, dependency upgrades, renaming, or cleanup.
   * Do not "improve" unrelated code.

7. **Protect user work**

   * Never discard uncommitted user changes.
   * Never use destructive Git commands (`reset --hard`, forced checkout, clean, etc.) unless the user explicitly authorizes the exact operation.
   * If the working tree contains relevant uncommitted changes, understand them before proceeding.

8. **Git discipline**

   * Review `git status`, recent commits, and relevant diffs before editing.
   * Keep commits/diffs focused when commits are requested.
   * Do not rewrite history or amend others' commits unless explicitly instructed.

9. **Verification**

   * Since local builds are prohibited, use static inspection, diff review, and reasoning for local verification.
   * Clearly distinguish verified facts from assumptions.
   * Never claim that the app builds, tests pass, or behavior was runtime-verified unless verified in Google AI Studio.

## Default Workflow

`sync → inspect history/status/diffs → understand existing changes → determine scope → interview if fundamental → make minimal change → review diff → hand off build/verification to Google AI Studio`

## Priority

When instructions conflict:

`user's explicit request > preservation of existing work > minimal change > convenience`
