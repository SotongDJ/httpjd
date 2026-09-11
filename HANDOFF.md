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

## 3. Next step: fixes

Fixes for the 5 findings above are being generated (or were generated, if
this handoff is stale) via the Claude Security `suggest-patches` job —
targeted patch files delivered beside the report, for review and manual
application. Nothing is auto-applied; the plugin never commits, pushes, or
opens a pull request on its own.

## 4. Standing constraints to keep honoring

- GPG-signed commits, always `git push --follow-tags`.
- Tag only on source changes, not docs/config-only ones.
- Prefer pixi over apt for tooling; conda-forge `openjdk` lacks `jmods` (no
  `jpackage` support) — relevant if the pixi pin question above is revisited.
