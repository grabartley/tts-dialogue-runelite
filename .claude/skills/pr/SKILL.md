---
name: pr
description: Create a PR for the tts-dialogue-runelite repo using a git worktree branched from latest main. Use for final branch, commit, push, and PR flow.
---

# PR

Use this skill for branch strategy, final validation, commit, push, and PR creation.
This skill is used directly by humans and also as a handoff step from the build skill.

## Workflow

1. Stage: `git add <files>`
2. Format: `./gradlew spotlessApply`
3. Test: `./gradlew test`
4. Build: `./gradlew clean build`
5. Commit: `git commit -m "<type>: <short lowercase descriptive present tense message>"`
- Types: feat, fix, refactor, test, docs, chore
6. Push: `git push origin <branch-name>`
7. Create PR with a body file to preserve markdown formatting and avoid shell interpolation issues:
	- Write body markdown to a temp file (example: `.claude/tmp/pr-body.md`) and include real newlines.
	- Create PR: `gh pr create --repo grabartley/tts-dialogue-runelite --base main --head <branch-name> --title "<title>" --body-file .claude/tmp/pr-body.md`
	- If updating an existing PR body, use: `gh pr edit <pr-number> --repo grabartley/tts-dialogue-runelite --body-file .claude/tmp/pr-body.md`
	- Title: `<type>: <description>` (same style as commit message)
	- Body: short, concise prose describing the FINAL STATE of the branch as it differs from `main`. Write in present tense as if the change has already landed. Group related behaviour into a few tight paragraphs. Reference specific files inline only when the path is essential context; otherwise leave file enumeration to the diff.
	- Forbidden phrasing because it describes the journey rather than the destination: "no longer", "now does", "restored", "refreshed", "renamed from", "previously", "fixed", "updated", "moved from X to Y", "added a", "the bug where". If a sentence describes how the branch differs from an earlier point on the same branch instead of describing what the merged code does, rewrite or delete it. Anything you addressed mid-development that has no observable effect vs `main` does not belong in the body.
	- Never use bulleted or numbered lists of file changes, class names being added, or method renames. Reviewers see that in the Files Changed tab already.
	- Never include automated-test breakdowns ("X tests pass", "Y new tests added"). CI runs the suite. At most, one sentence on the *kinds* of tests added (unit tests for a voice manager, etc.) when that's actually useful context.
	- Keep it concise. Do not waffle. If a paragraph can be cut without losing reviewer-relevant signal, cut it.
	- Attach screenshots whenever the change has a visible surface (plugin config panel, in-game dialogue, RuneLite overlay). Capture the relevant state in the dev client or wherever the change is visible, and either drag the file into the GitHub PR description after creation or upload it through the GitHub web UI; reference the resulting `user-attachments` URL in the body next to the paragraph it illustrates. Skip screenshots only when the change has no rendered output.
	- Wrap class names, commands, and identifiers in backticks inside the markdown file, not inline shell args.
	- Always include a closing reference like `Closes #<issue-number>` so the PR Development section is linked to the issue being worked on.
8. After merge, clean up: `cd ../tts-dialogue-runelite && git worktree remove ./.claude/worktrees/tts-<branch-name>`

## Conventions

- Always branch from latest `main`, never from other branches
- Use a fresh worktree per PR, don't reuse worktrees across branches
- Worktree path: `./.claude/worktrees/tts-<branch-name>` (sibling directory)
- Branch names: kebab-case (e.g. `fix-voice-fallback`, `add-static-npc-map`)
- Commit messages: `<type>: <lowercase description>`, no period at end
- Types: feat, fix, refactor, test, docs, chore
- PR descriptions: tight, present-tense prose describing the final state vs `main`. No journey language ("no longer", "now", "restored", "fixed", etc.). No file-change lists. No automated-test pass-counts. Cut anything that doesn't help the reviewer.
- Attach screenshots for any change with a visible surface, drag-uploaded into the GitHub PR description so the body links the `user-attachments` URL beside the paragraph it illustrates.
- PR titles and descriptions must be written as public-facing text, since this repo is public and anyone can view them
- Always link the PR Development section to the active issue via `Closes #<issue-number>` in the PR body
- Pre-commit must complete successfully: format, test, build
- Run `./gradlew spotlessApply` before staging so CI `spotlessCheck` stays green
- No emoji in commit messages or PR titles

## Related Skills

- worktree, used first for fresh branch and isolated directory setup

## Build Skill Integration

- If invoked after build work, confirm the linked issue is in `QA testing` before final handoff.
- Do not move issue to `Done`, that is reserved for human QA completion.
