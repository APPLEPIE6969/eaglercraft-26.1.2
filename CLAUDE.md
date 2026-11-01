# CLAUDE.md

Operating rules for every AI session working in this repository. Read this file first.

## The project

EaglercraftX 26.1.2 — Minecraft 26.1.2 in the browser (TeaVM → WebGL2), deployed
from `main` to GitHub Pages: https://applepie6969.github.io/eaglercraft-26.1.2/

- **`origin/main` is the single source of truth.** The sandbox resets periodically
  and destroys unpushed commits AND unpushed files outside the repo. Work only
  exists once it is committed, pushed, CI-green, and verified on the live site.
- **Never guess vanilla behavior.** The ground truth is the decompile:
  `docs/decompiled-26.1.2-full-src.zip` (the complete jar, 6,882 Java files) and
  the loose reference files in `docs/decompiled-26.1.2-reference/`.
- **What is implemented vs missing:** `docs/IMPLEMENTATION-COVERAGE.md` — keep it
  current whenever you add or complete a system.
- Layout: `sources/teavm/java/` (the port), `gateway/` (Bungee / Velocity /
  PlaceholderServer), `tests/e2e/` (Playwright suite), `scripts/` (build tooling),
  `public/` (deployed bytes), `docs/`.

## worklog.md — mandatory rules

`worklog.md` at the repo root is the project's memory. **It must be very detailed
with what was done.**

1. Append one entry per work session covering, concretely:
   - what was changed, file by file, and why;
   - commit hashes and subjects; CI run IDs and their outcomes;
   - verification actually performed and its real results (measurements,
     probe output, screenshots, test counts);
   - bugs found, root causes, decisions made and the reasoning behind them;
   - what is still open and what the next session must pick up.
2. "Very detailed" means: a fresh session with no other context can reconstruct
   the full project state from `worklog.md` alone. One-line summaries are not
   acceptable.
3. Commit and push `worklog.md` in the same push as the work it describes —
   an unpushed worklog will be lost to the next sandbox reset.
4. If `worklog.md` is missing after a reset, rebuild it from `git log` and the
   session context before starting new work.

**Never write tokens in the file.** No API tokens, PATs, passwords, cookies,
private keys, or any other credentials in `worklog.md` — or in any other
committed file, commit message, or CI log. Not even partially or redacted.
Refer to a secret only by where it lives, never by its contents.

## Secrets

- The GitHub PAT lives only in the `origin` remote URL and (optionally)
  `scripts/.gh_token`. Both are covered by `.gitignore` — never commit them,
  never print them, never copy them into any file.
- `.gitignore` ignores `scripts/*` with an explicit whitelist
  (`!scripts/<name>`). A new helper script is silently NOT committed until you
  add its whitelist line — always check `git status` after adding scripts.
- If a secret ever reaches a commit, treat it as compromised: revoke/rotate it
  immediately, then scrub history if feasible.

## Definition of done

1. Code committed and pushed to `main`.
2. CI ("Build & Deploy" workflow) fully green: `build` → `geometry-unit` →
   `e2e` → `deploy`.
3. The change verified against the deployed bytes on the live site, not just
   the local bundle.
4. `worklog.md` updated (very detailed, no tokens) and included in the push.

## Sandbox recovery (expected routine)

After a reset: `git fetch origin && git reset --hard origin/main`, confirm the
PAT is still embedded in the remote URL (`git remote get-url origin | grep -q '@'`),
rebuild any missing gitignored tooling, then read `worklog.md` to resume where
the last session left off.
