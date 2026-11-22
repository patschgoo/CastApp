#!/usr/bin/env bash
# Refreshes the vendored copy of whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp).
# Only what a CPU build for Android (ARM) and a desktop test build (x86) needs is kept.
#
# Usage: app/src/main/cpp/update-whisper.sh [tag] [existing checkout of that tag]
# Without a checkout the tag is cloned into a temporary folder.
set -euo pipefail
WHISPER_TAG="${1:-v1.9.5}"
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/whisper.cpp"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ -n "${2:-}" ]; then
    src="$(cd "$2" && pwd)"
else
    git clone --quiet --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp "$tmp/src"
    src="$tmp/src"
fi
rev="$(git -C "$src" rev-parse HEAD)"

rm -rf "$dest"
mkdir -p "$dest/src/aneforge" "$dest/ggml/src"
cp "$src/LICENSE" "$dest/"
cp -r "$src/include" "$dest/"
cp "$src/src/whisper.cpp" "$src/src/whisper-arch.h" "$dest/src/"
cp "$src/src/aneforge/whisper-aneforge.h" "$src/src/aneforge/whisper-aneforge.cpp" "$dest/src/aneforge/"
cp "$src/ggml/CMakeLists.txt" "$dest/ggml/"
cp -r "$src/ggml/cmake" "$src/ggml/include" "$dest/ggml/"
find "$src/ggml/src" -maxdepth 1 -type f -exec cp {} "$dest/ggml/src/" \;
cp -r "$src/ggml/src/ggml-cpu" "$dest/ggml/src/"
# Other CPU families and optional extras this build does not use.
rm -rf "$dest/ggml/src/ggml-cpu/arch/loongarch" "$dest/ggml/src/ggml-cpu/arch/powerpc" \
       "$dest/ggml/src/ggml-cpu/arch/riscv" "$dest/ggml/src/ggml-cpu/arch/s390" \
       "$dest/ggml/src/ggml-cpu/arch/wasm" "$dest/ggml/src/ggml-cpu/spacemit" \
       "$dest/ggml/src/ggml-cpu/kleidiai"
echo "$WHISPER_TAG $rev" > "$dest/VERSION"
echo "whisper.cpp $WHISPER_TAG copied to $dest ($(du -sh "$dest" | cut -f1))"
