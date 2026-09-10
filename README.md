# httpjd

A small, hardened HTTP **file server with resumable-download support** — the
Java port of [`httprd`](https://github.com/SotongDJ/httprd).

`httpjd` ships in two forms from one shared, zero-dependency core:

- a **single static native binary** (the CLI), built with GraalVM `native-image`;
- a **cross-platform desktop app** (Swing), packaged per-OS with `jpackage`
  for Windows, Linux, and macOS.

It serves `GET`/`HEAD` only, sandboxes every request against the document root,
supports HTTP Range requests, and sets defensive headers — the same behaviour,
and the same vulnerability test suite, as the original Rust implementation.

## Features

- `GET` / `HEAD` only; every other method gets `405 Method Not Allowed`.
- Path-traversal safe: percent-decoded paths are sandboxed, `..` is dropped,
  null bytes are rejected, and symlinks that escape the root return `403`.
- HTTP Range requests (`206 Partial Content`, `416` when unsatisfiable) for
  resumable downloads, with `Accept-Ranges: bytes`.
- `X-Content-Type-Options: nosniff` on file responses; `Content-Security-Policy`
  on directory listings; no version-leaking `Server` header.
- Optional `ls -hal`-style directory listing (`-i`), otherwise `403`.

## Project layout

| Module        | What it is                                                        |
|---------------|-------------------------------------------------------------------|
| `httpjd-core` | The server (`com.sun.net.httpserver`, no external deps) + tests.  |
| `httpjd-cli`  | CLI entry point → static native binary.                           |
| `httpjd-gui`  | Swing desktop frontend → `jpackage` installers.                   |

## Requirements

**Java 21 or newer**, both to run and to build. That floor comes from the code,
not just convention: `FileServer` dispatches requests on
`Executors.newVirtualThreadPerTaskExecutor()`, and virtual threads
([JEP 444](https://openjdk.org/jeps/444)) were finalized in Java 21 — in 19 and
20 they are a disabled-by-default preview API, and earlier releases lack them
entirely. The jars are compiled with `maven.compiler.release=21` (class file
version 65), so an older JVM refuses to load them with
`UnsupportedClassVersionError`. There is no upper bound — 22 through 25 work.

| What you are doing | What you need |
|--------------------|---------------|
| Run `httpjd.jar` (CLI) | Java 21+ runtime, including the `jdk.httpserver` module |
| Run `httpjd-gui.jar` (desktop) | Java 21+ runtime, including `jdk.httpserver` and `java.desktop` — not a headless image |
| Run the static native binary | nothing; no JVM required |
| Build the jars, run the tests | any JDK 21+ |
| Build the native binary (`-Pnative`) | GraalVM JDK 21+ with `native-image` |
| Build the desktop installers (`-Pjpackage`) | a full JDK 21+ that ships `jmods` |

A plain JRE is enough to run the jars: both modules are present in every
standard JDK and JRE distribution, and only a stripped-down custom `jlink`
image needs them added explicitly. There are no external runtime dependencies —
the single declared dependency is JUnit, at test scope.

## Usage (CLI)

```
httpjd [OPTIONS]

Options:
  -p, --port <PORT>  Port to listen on            [default: 8080]
  -d, --dir <DIR>    Root directory to serve      [default: .]
  -i, --index        Show directory listing when no index file exists
  -h, --help         Print help
  -V, --version      Print version
```

```sh
httpjd -d ./public -p 8080 -i
```

## Download

Prebuilt jars for each `vX.Y.Z` tag are on the
[Releases page](https://github.com/SotongDJ/httpjd/releases) (built and
published by CI):

```sh
java -jar httpjd.jar -d . -p 8080 -i   # CLI
java -jar httpjd-gui.jar               # desktop app
```

Both jars need a **Java 21+** runtime; see [Requirements](#requirements).

The static native binary and the per-OS installers are not published —
build them from source as described below.

## Build

A Maven wrapper is included, so no Maven install is required. Any **JDK 21+**
builds the jars and runs the tests; the native binary additionally needs
**GraalVM JDK 21+**, and the installers a full JDK that ships `jmods` — see
[Requirements](#requirements).

If you use [pixi](https://pixi.sh), the included `pixi.toml` pins conda-forge
`openjdk` 25 as the build JDK (comfortably above the 21 floor) — prefix
commands with `pixi run`:

```sh
pixi run ./mvnw test
```

### Run the tests

```sh
./mvnw test
```

### Runnable jars (any OS, needs a Java 21+ runtime)

```sh
./mvnw -DskipTests package
java -jar httpjd-cli/target/httpjd.jar -d . -p 8080 -i   # CLI
java -jar httpjd-gui/target/httpjd-gui.jar               # desktop app
```

### Static native binary (Linux, no runtime needed)

The fully-static binary links against **musl**. One-time toolchain setup:

```sh
sudo apt-get install -y musl-tools           # provides musl-gcc
export GRAALVM_HOME="$JAVA_HOME"              # a GraalVM with native-image
scripts/setup-musl.sh                         # builds static zlib + compiler wrapper
export PATH="$PWD/.musl/bin:$PATH"
```

Then build it (requires GraalVM as the build JDK):

```sh
./mvnw -Pnative -pl httpjd-cli -am package
./httpjd-cli/target/httpjd --help
```

The result is `httpjd-cli/target/httpjd` — a self-contained executable with no
JVM and no shared-library dependencies (verify with `ldd`/`file`).

### Cross-platform desktop installer (`jpackage`)

Run on the OS you want to target; `jpackage` emits that platform's artifact
(Linux `.deb`/`.rpm`, macOS `.dmg`/`.pkg`, Windows `.exe`/`.msi`):

```sh
./mvnw -Pjpackage -pl httpjd-gui -am -DskipTests verify
# installers land in httpjd-gui/target/dist/
```

The jpackage step is bound to the `verify` phase, so `package` alone skips it.
It also needs a full JDK 21+ that ships `jmods` (jlink support) — Oracle,
Temurin, and GraalVM tarballs work; conda-forge's `openjdk` does not.

Override the artifact type with `-Djpackage.type=deb` (or `rpm`, `dmg`, `pkg`,
`exe`, `msi`, `app-image`).

## Releasing

Releases are cut by pushing a GPG-signed version tag; CI
(`.github/workflows/release.yml`) builds the jars, runs the tests, and
publishes them as a GitHub release. Tags are only added when source
changes (docs-only changes are not tagged), and the commit and tag are
pushed together:

```sh
# bump the version in the poms, pixi.toml, and Cli.java first
git tag -s vX.Y.Z -m "httpjd X.Y.Z"
git push --follow-tags
```

## License

GNU Lesser General Public License v2.1 — see [LICENSE](LICENSE).
