#!/usr/bin/env bash
#
# Prepare a musl toolchain for GraalVM `native-image --static --libc=musl`.
#
# GraalVM needs two things on Linux to emit a fully static binary:
#   1. a musl C compiler reachable as `x86_64-linux-musl-gcc`
#   2. a static zlib (libz.a) compiled against musl
#
# `musl-gcc` comes from the distro package `musl-tools` (install it first:
#   sudo apt-get install -y musl-tools
# ). This script builds a musl-linked static zlib and drops a correctly-named
# compiler wrapper into ./.musl/bin, then prints the PATH export to use.
#
# Usage:
#   sudo apt-get install -y musl-tools
#   scripts/setup-musl.sh
#   export PATH="$PWD/.musl/bin:$PATH"
#   ./mvnw -Pnative -pl httpjd-cli -am package

set -euo pipefail

ZLIB_VERSION="1.3.1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="$ROOT/.musl"
BINDIR="$PREFIX/bin"

if ! command -v musl-gcc >/dev/null 2>&1; then
    echo "error: musl-gcc not found. Install it with: sudo apt-get install -y musl-tools" >&2
    exit 1
fi

mkdir -p "$BINDIR"

# native-image's --libc=musl invokes `x86_64-linux-musl-gcc`; point it at musl-gcc.
if [ ! -e "$BINDIR/x86_64-linux-musl-gcc" ]; then
    ln -sf "$(command -v musl-gcc)" "$BINDIR/x86_64-linux-musl-gcc"
    echo "linked x86_64-linux-musl-gcc -> $(command -v musl-gcc)"
fi

# Build static zlib against musl, installed under the musl sysroot.
MUSL_SYSROOT="$(dirname "$(dirname "$(readlink -f "$(command -v musl-gcc)")")")/lib/x86_64-linux-musl"
if [ -f "$MUSL_SYSROOT/libz.a" ]; then
    echo "musl static zlib already present at $MUSL_SYSROOT/libz.a"
else
    BUILD="$(mktemp -d)"
    echo "building zlib $ZLIB_VERSION against musl in $BUILD ..."
    curl -fsSL "https://zlib.net/zlib-${ZLIB_VERSION}.tar.gz" -o "$BUILD/zlib.tar.gz"
    tar -C "$BUILD" -xzf "$BUILD/zlib.tar.gz"
    (
        cd "$BUILD/zlib-${ZLIB_VERSION}"
        CC=musl-gcc ./configure --static --prefix="$PREFIX/zlib"
        make -j"$(nproc)"
        make install
    )
    # Place the static lib and headers where musl-gcc will find them.
    cp "$PREFIX/zlib/lib/libz.a" "$(dirname "$(readlink -f "$(command -v musl-gcc)")")/../lib/" 2>/dev/null \
        || sudo cp "$PREFIX/zlib/lib/libz.a" /usr/lib/x86_64-linux-musl/ 2>/dev/null \
        || echo "note: copy $PREFIX/zlib/lib/libz.a onto the musl library path if linking fails"
    rm -rf "$BUILD"
fi

echo
echo "musl toolchain ready. Now run:"
echo "  export PATH=\"$BINDIR:\$PATH\""
echo "  ./mvnw -Pnative -pl httpjd-cli -am package"
