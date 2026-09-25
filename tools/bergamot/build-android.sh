#!/usr/bin/env bash
#
# Builds libdictate_bergamot.so — Mozilla's Bergamot translation engine plus our JNI bridge — for
# on-device translation (issue #424), and drops it into app/src/main/jniLibs/<abi>/.
#
# Like the sherpa-onnx libraries, the result is a reproducible build artifact and is not committed
# (app/src/main/jniLibs/ is ignored). Sources are fetched into tools/bergamot/build/, which is ignored
# as well; a full arm64 build takes a few minutes on 4 cores.
#
# Usage:  tools/bergamot/build-android.sh [abi ...]      (default: arm64-v8a)
#
set -euo pipefail

# mozilla/translations at the commit this was first built from (2026-09-18). inference/ holds the
# maintained bergamot-translator and Marian fork; the standalone bergamot-translator repo is archived.
TRANSLATIONS_COMMIT="a6310e24669df32a9098faadccbb1206448b51cb"
MIN_SDK=26
if [ "$#" -gt 0 ]; then ABIS=("$@"); else ABIS=("arm64-v8a"); fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/build"
SRC="$WORK/src"

SDK="${ANDROID_HOME:-$(grep '^sdk.dir=' "$REPO_ROOT/local.properties" | cut -d= -f2)}"
NDK_VERSION="$(grep '^ndk ' "$REPO_ROOT/gradle/tools.versions.toml" | sed -E 's/.*"(.*)".*/\1/')"
CMAKE_VERSION="$(grep '^cmake ' "$REPO_ROOT/gradle/tools.versions.toml" | sed -E 's/.*"(.*)".*/\1/')"
NDK="$SDK/ndk/$NDK_VERSION"
CMAKE="$SDK/cmake/$CMAKE_VERSION/bin/cmake"
NINJA="$SDK/cmake/$CMAKE_VERSION/bin/ninja"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for tool in "$CMAKE" "$NINJA" "$STRIP"; do
  [ -x "$tool" ] || { echo "Missing $tool — install NDK $NDK_VERSION and CMake $CMAKE_VERSION via the SDK manager." >&2; exit 1; }
done

# Only inference/ and the submodules a CPU build needs; the rest of the repo is the training pipeline.
if [ ! -d "$SRC/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/mozilla/translations.git "$SRC"
  git -C "$SRC" sparse-checkout set --no-cone /inference /.gitmodules
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null)" != "$TRANSLATIONS_COMMIT" ]; then
  git -C "$SRC" fetch --depth 1 origin "$TRANSLATIONS_COMMIT"
  git -C "$SRC" checkout --quiet "$TRANSLATIONS_COMMIT"
fi
# Marian's CMakeLists runs `git submodule update --init --recursive` unconditionally, and from inside the
# repository that means every submodule of it: FBGEMM, NCCL and onnxjs+Eigen (GPU/x86 code a phone never
# compiles) and the training pipeline's kenlm, fast_align, extract-lex, preprocess, marian-dev and emsdk —
# about 400 MB nobody builds. Marked "none", `submodule update` skips them.
for unused in fbgemm nccl onnxjs simple-websocket-server; do
  git -C "$SRC" config "submodule.inference/marian/src/3rd_party/$unused.update" none
done
for unused in fast_align extract-lex 3rd_party/kenlm 3rd_party/marian-dev 3rd_party/preprocess inference/3rd_party/emsdk; do
  git -C "$SRC" config "submodule.$unused.update" none
done
git -C "$SRC" submodule update --init --depth 1 -- \
  inference/3rd_party/ssplit-cpp \
  inference/marian-fork/src/3rd_party/sentencepiece \
  inference/marian-fork/src/3rd_party/intgemm \
  inference/marian-fork/src/3rd_party/ruy \
  inference/marian-fork/src/3rd_party/simd_utils
git -C "$SRC/inference/marian-fork/src/3rd_party/ruy" submodule update --init --depth 1 -- third_party/cpuinfo

# NDK 29's clang rejects a constexpr cast of -1 into sentencepiece's ScriptType enum, which older
# compilers only warned about (and -Wno-enum-constexpr-conversion no longer silences). It is trainer
# code a phone never runs, but Marian links the trainer library, so it has to compile.
SENTENCEPIECE="$SRC/inference/marian-fork/src/3rd_party/sentencepiece"
if ! git -C "$SENTENCEPIECE" apply --reverse --check "$HERE/patches/sentencepiece-constexpr-enum.patch" 2>/dev/null; then
  git -C "$SENTENCEPIECE" apply "$HERE/patches/sentencepiece-constexpr-enum.patch"
fi

# CMake 4 refuses projects declaring a minimum below 3.5, and several of Marian's dependencies do. The
# -D flag covers our configure; the environment variable also reaches the PCRE2 ExternalProject,
# which runs its own configure step.
export CMAKE_POLICY_VERSION_MINIMUM=3.5

for ABI in "${ABIS[@]}"; do
  OUT="$WORK/out-$ABI"
  # Marian's CPU target. The x86 one only matters for the emulator; intgemm picks SSE/AVX at runtime.
  case "$ABI" in
    arm64-v8a) BUILD_ARCH=armv8-a ;;
    armeabi-v7a) BUILD_ARCH=armv7-a ;;
    x86_64) BUILD_ARCH=x86-64 ;;
    *) echo "No Marian target for $ABI" >&2; exit 1 ;;
  esac
  "$CMAKE" -S "$HERE" -B "$OUT" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$MIN_SDK" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBERGAMOT_SOURCE_DIR="$SRC/inference" \
    -DGIT_SUBMODULE=OFF \
    -DBUILD_ARCH="$BUILD_ARCH" \
    -DUSE_WASM_COMPATIBLE_SOURCE=OFF \
    -DCOMPILE_CUDA=OFF \
    -DUSE_STATIC_LIBS=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DSSPLIT_USE_INTERNAL_PCRE2=ON \
    -DSPM_ENABLE_SHARED=OFF \
    -DSPM_ENABLE_TCMALLOC=OFF \
    -DUSE_TCMALLOC=OFF \
    -DCMAKE_CXX_FLAGS="-Wno-enum-constexpr-conversion"
  "$NINJA" -C "$OUT" -j "$(( $(nproc) / 2 > 0 ? $(nproc) / 2 : 1 ))" dictate_bergamot

  DEST="$REPO_ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  "$STRIP" --strip-unneeded -o "$DEST/libdictate_bergamot.so" "$OUT/libdictate_bergamot.so"
  echo "→ $DEST/libdictate_bergamot.so ($(du -h "$DEST/libdictate_bergamot.so" | cut -f1))"
done
