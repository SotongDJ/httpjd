#!/usr/bin/env bash
#
# Prepare a musl toolchain for GraalVM `native-image --static --libc=musl`.
#
# GraalVM needs two things on Linux to emit a fully static binary:
#   1. a musl C compiler reachable as `x86_64-linux-musl-gcc`
#   2. a static zlib (libz.a) compiled against musl, on the linker's search path
#
# `musl-gcc` comes from the distro package `musl-tools`. native-image invokes
# the linker with a scrubbed environment, so LIBRARY_PATH does NOT propagate —
# the static libz.a must be dropped into GraalVM's own musl static-lib dir,
# which is already on the linker's -L path (and lives under your home, so no
# sudo is needed).
#
# Usage:
#   sudo apt-get install -y musl-tools
#   export GRAALVM_HOME="$HOME/tools/graalvm-jdk-25.0.3+9.1"   # or your GraalVM
#   scripts/setup-musl.sh
#   export PATH="$PWD/.musl/bin:$PATH"
#   ./mvnw -Pnative -pl httpjd-cli -am package

set -euo pipefail

ZLIB_VERSION="1.3.1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="$ROOT/.musl"
BINDIR="$PREFIX/bin"

GRAALVM_HOME="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [ -z "$GRAALVM_HOME" ] || [ ! -d "$GRAALVM_HOME/lib/static/linux-amd64/musl" ]; then
    echo "error: set GRAALVM_HOME (or JAVA_HOME) to a GraalVM with native-image." >&2
    echo "       expected dir: \$GRAALVM_HOME/lib/static/linux-amd64/musl" >&2
    exit 1
fi
if ! command -v musl-gcc >/dev/null 2>&1; then
    echo "error: musl-gcc not found. Install it with: sudo apt-get install -y musl-tools" >&2
    exit 1
fi

mkdir -p "$BINDIR"

# native-image's --libc=musl invokes `x86_64-linux-musl-gcc`; point it at musl-gcc.
ln -sf "$(command -v musl-gcc)" "$BINDIR/x86_64-linux-musl-gcc"
echo "linked x86_64-linux-musl-gcc -> $(command -v musl-gcc)"

# Build static zlib against musl.
if [ ! -f "$PREFIX/zlib/lib/libz.a" ]; then
    BUILD="$(mktemp -d)"
    echo "building zlib $ZLIB_VERSION against musl ..."
    curl -fsSL "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" \
        -o "$BUILD/zlib.tar.gz"
    tar -C "$BUILD" -xzf "$BUILD/zlib.tar.gz"
    (
        cd "$BUILD/zlib-${ZLIB_VERSION}"
        CC=musl-gcc ./configure --static --prefix="$PREFIX/zlib"
        make -s -j"$(nproc)"
        make -s install
    )
    rm -rf "$BUILD"
fi

# Drop libz.a where native-image's linker already looks (GraalVM's musl dir).
cp "$PREFIX/zlib/lib/libz.a" "$GRAALVM_HOME/lib/static/linux-amd64/musl/libz.a"
echo "installed libz.a -> $GRAALVM_HOME/lib/static/linux-amd64/musl/libz.a"

echo
echo "musl toolchain ready. Now run:"
echo "  export PATH=\"$BINDIR:\$PATH\""
echo "  ./mvnw -Pnative -pl httpjd-cli -am package"
