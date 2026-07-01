---
name: build
description: Build or implement a feature for the Voiced Dialogue RuneLite plugin, optionally from a GitHub issue. Use when asked to build, implement, or ship scoped work and keep project board status updated.
---

# Build

## Critical Rules

1. Always tie build work to a GitHub issue.
2. Run the `worktree` skill first before any issue moves, coding, or validation.
3. Keep issue project status in sync during execution.
4. Behavioral code changes require unit tests. Test classes must mirror the production class name under test with a `Test` suffix and live in the same package structure under `src/test/java`. Docs-only or config-only changes (for example `README.md`, `.gitignore`, or workflow and skill files) are exempt.
5. Use the `create-issue` skill whenever build work does not already have a GitHub issue.
6. Assign the issue being worked on to the developer running the build workflow before moving it to `In progress`. Detect the current GitHub user with `gh api user --jq .login`, do not guess.
7. Run the pr skill as part of build after validation passes.
8. Move issue to `QA testing` only after the PR is opened and CI has completed green. If CI fails, keep the issue in `In progress` until it passes.
9. After PR creation and `QA testing` transition, always provide a detailed manual QA checklist to the developer.
10. If PR code changes after the PR is opened, check whether the PR description still matches the current branch state, and update it if needed so it reflects the final state only.
11. Stop at QA testing, human performs final verification and moves to Done.
12. Every code change must also update any docs it invalidates. Audit `README.md`, in-repo docs, and the linked issue body before committing; ship doc edits in the same PR as the code change.
13. If QA finds issues after handoff, re-enter the build flow for the same issue: move it back to `In progress` and continue on the existing branch and PR. Do not open a new issue for the same scope.
14. Plugin `src/main` sources must stay Java 11 compatible: the Plugin Hub's `build=standard` compiles them at release 11 with its own `build.gradle`. Do not add records, pattern-matching `instanceof`, `Stream.toList()`, or other Java 12+ syntax/APIs to `src/main`. `compileJava` pins `options.release=11` so `./gradlew build` fails locally on a violation; keep it that way. Tests and the `engine-kokoro` subproject may use Java 17.

## Workflow

1. Run the `worktree` skill to create a fresh isolated branch worktree, then perform all implementation and validation work inside that worktree.
2. Capture scope from the request.
3. If an issue number or URL is provided, read it first with gh:
- `gh issue view <number> --repo grabartley/runelite-voiced-dialogue`
- Extract acceptance criteria, constraints, and references.
4. If no issue is provided, invoke the `create-issue` skill before coding.
5. Use the created issue as the tracking artifact for all subsequent status moves.
6. Assign the issue to the developer who called build (detect the login with `gh api user --jq .login`).
7. Move the issue to `In progress`.
8. Implement the feature.
9. Run relevant automated tests and a local validation pass for changed behavior.
10. Run manual validation via run-game-client when dialogue or voice behavior changes.
11. Invoke the pr skill for branch strategy, final checks, commit, push, and PR creation.
12. If you make additional code changes after the PR is opened, re-check the PR description and update it when necessary so it describes only the final shipped scope.
13. Wait for CI to complete on the PR and report status.
14. Move issue to `QA testing` only once CI is green and the PR is ready for human verification. If CI fails, keep the issue in `In progress`, fix the failures on the same branch, and re-validate before transitioning.
15. Provide a detailed manual QA checklist that the developer can run step by step.

## Board Status Policy

- Use these exact status values from project `grabartley/projects/3`:
- `Backlog`: issue created, not started
- `Ready`: scoped and ready to start
- `In progress`: active implementation
- `QA testing`: implementation complete, awaiting human validation
- `Done`: human-only final move after QA signoff

- Required transitions for build flow:
- Start work: set to `In progress`
- After PR creation, CI green, and QA handoff: set to `QA testing`
- Do not move to `Done` inside this skill

## Issue Creation

- When no issue exists yet, use the `create-issue` skill instead of drafting an ad hoc issue body inside this skill.
- Issues created by `create-issue` should stay unassigned by default unless the user asked for an assignee.
- Before implementation starts, assign the issue to the developer who called build, then move it from `Ready` to `In progress`.
- Keep using that issue for PR linkage and board status changes through the rest of the build flow.

## Related Skills

- worktree, required first step for isolated branch setup
- create-issue, required when build work starts without an existing GitHub issue
- pr, required for branch, commit, push, and PR creation during build flow
- run-game-client, use for manual validation before handoff
