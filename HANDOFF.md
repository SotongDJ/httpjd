# Handoff — httpjd

Repo: `httpjd` (a Java port of the Rust `httprd` HTTP file server). Branch `main`.

## 1. Done and shipped

**Java version requirement, documented.** The floor is **Java 21**, driven by
`Executors.newVirtualThreadPerTaskExecutor()` in
`httpjd-core/src/main/java/io/github/sotongdj/httpjd/core/FileServer.java`
(JEP 444, finalized in 21). Verified empirically by compiling with
`--release 17/19/20` (fails) vs `21` (succeeds). Documented in `README.md`
(new **Requirements** section plus a run/build matrix), `pixi.toml` (comment
explaining the build-JDK pin vs. the 21 floor), and
`.github/workflows/release.yml` (comment on the `java-version: '21'` step).

Committed, GPG-signed, and pushed to `main` already — docs/config-only
change, so no tag was created, per the project's release-tagging policy
(GPG-signed tags only on source changes; always `git push --follow-tags`).

**Open, not acted on:** `pixi.toml` still pins `openjdk = ">=25.0.2,<26"`
rather than relaxing it to `>=21`. Left deliberately — the pin is the
reproducible build environment, 21 is the compatibility floor, and they are
different concerns — but relaxing it is a reasonable ask if requested.

## 2. Security scan completed

A whole-repository Claude Security scan (medium effort, unscoped, no focus
filtering — the repo is only 23 tracked files) ran and came back
`verification.status: verified`. The report lives in a
`CLAUDE-SECURITY-<timestamp>/` directory at the repo root (its own
`.gitignore` keeps it out of commits unless that file is deleted).

**5 findings survived** independent panel verification:

| ID | Severity | File:line | Issue |
|---|---|---|---|
| F1 | **HIGH** | `httpjd-core/.../RequestHandler.java` (`serveDir`) | Auto-served `index.html`/`index.htm` follows symlinks without re-checking root containment — bypasses the sandbox, arbitrary local file read via an unauthenticated GET |
| F2 | MEDIUM | `httpjd-cli/.../Cli.java` (`main`) | CLI hardcodes bind to `0.0.0.0`, no `--host`/`-b` flag, zero auth anywhere in `RequestHandler` |
| F3 | MEDIUM | `httpjd-gui/.../Gui.java` (`startServer`) | Same `0.0.0.0` hardcode in the GUI; status label misleadingly shows `localhost` |
| F4 | MEDIUM | `scripts/setup-musl.sh` | zlib tarball downloaded and built with no checksum/signature verification (dev-only, opt-in native-image/musl setup script, not wired into CI) |
| F5 | MEDIUM | `httpjd-core/.../RequestHandler.java` (`serveFile`) | TOCTOU: containment check resolves the path once, but later opens re-resolve it, so a symlink swap mid-race can escape the sandbox |

2 other candidates were proposed and unanimously rejected by the
verification panel (the Maven wrapper's optional checksum skip over an
official HTTPS-only URL; the release workflow's `contents: write` permission
matching what a collaborator already has) — not in the report.

Read the report's `CLAUDE-SECURITY-RESULTS.md` for full exploit scenarios,
preconditions, and recommended fixes per finding.

## 3. Fix run in progress (interrupted, needs resuming)

The Claude Security `suggest-patches` job was started for **all 5 findings**
(F1–F5) against the report in `CLAUDE-SECURITY-20260910-165351/`. It was
paused mid-run — nothing has been finalized, nothing is in `patches/` yet,
and the working ground (gitignored) is still on disk for whoever resumes.

- **PATCH BASE** (the commit every patch is built against): the repo's HEAD
  at the time the run started. The report's own scan was taken one commit
  earlier; the only difference between the two is this handoff file being
  added, which touches none of the 5 flagged files, so the report was
  treated as still current rather than re-scanning.
- **Working ground:** `CLAUDE-SECURITY-20260910-165351/.claude-security-run/patch-20260911-005435/`
  (gitignored, holds one `scratch-F<n>/` full checkout per finding plus each
  earned `F<n>.diff`).
- **F1 — HIGH (index-file symlink bypass in `serveDir`): generator and
  verifier both passed, adversarial review was interrupted before returning.**
  - Generator produced a single-file, minimal patch to
    `httpjd-core/.../RequestHandler.java`: `serveDir()`'s index-file lookup
    now calls `p.toRealPath()` and re-checks `startsWith(root)` — the same
    containment check `route()` already does — before serving, falling
    through (as if the index file didn't exist) when that fails.
  - Verifier returned **PASS**, all three claims **CONFIDENT** (targeted:
    one file, one hunk; no new vulnerability: the change is strictly more
    restrictive; behaviour unchanged: legitimate in-root index files, symlinked
    or not, still serve identically — only the exploit input is newly turned
    away). All 31 project tests pass. The exact changed branch is `untested`
    by the project's own suite (no existing test creates a symlinked
    `index.html`); the verifier validated it instead with its own standalone
    probe (not a repo test) showing the exploit blocked pre/post patch.
  - The staged diff is saved at `.../patch-20260911-005435/F1.diff`; the scratch
    checkout `scratch-F1/` still holds the staged (uncommitted) change.
  - **Still needed for F1:** the adversarial pass (a fresh researcher, scoped
    only to this diff, asked "what can an attacker do with this change that
    they couldn't before?") — re-run it from scratch; the diff above is the
    input. If it comes back clean, `F1.patch`/`F1.md` can be written.
- **F2, F3, F4, F5 — not started.** No scratch workspace exists for any of
  these yet.

**To resume:** open `jobs/suggest-patches.md` in the Claude Security skill
and continue from step 4 (adversarial pass) for F1, then run F2–F5 through
generate → verify → adversarial → patch, one finding at a time (only one
scratch workspace open at once — remove each before starting the next), then
step 5 (`patches.json` + `patch_artifacts.py`) once all five are settled.
Nothing here has touched the user's real checkout; the interrupted run is
safe to resume or to abandon and restart clean (delete the report's
`.claude-security-run/` working ground either way).

## 4. Standing constraints to keep honoring

- GPG-signed commits, always `git push --follow-tags`.
- Tag only on source changes, not docs/config-only ones.
- Prefer pixi over apt for tooling; conda-forge `openjdk` lacks `jmods` (no
  `jpackage` support) — relevant if the pixi pin question above is revisited.
