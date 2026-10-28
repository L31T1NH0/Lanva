#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
oxide_revision=8e0770ea9fb13f585134a7aa376682e514cd2ff6
oxide_source="$project_root/build/oxide"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_root="${ANDROID_NDK_HOME:-${sdk_root:+$sdk_root/ndk/28.2.13676358}}"
if [[ -z "$ndk_root" || ! -d "$ndk_root" ]]; then
    echo 'NDK ausente. Instale ndk;28.2.13676358 e defina ANDROID_HOME, ou defina ANDROID_NDK_HOME.' >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) ndk_host=linux-x86_64 ;;
    Darwin) ndk_host=darwin-x86_64 ;;
    *) echo 'Execute este script no Linux ou macOS.' >&2; exit 1 ;;
esac
ndk_bin="$ndk_root/toolchains/llvm/prebuilt/$ndk_host/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
export RUSTUP_TOOLCHAIN=1.90.0
export CARGO_TARGET_DIR="$project_root/build/rust"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
if [[ ! -x "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" ]]; then
    echo "Compilador NDK não encontrado: $CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" >&2
    exit 1
fi
command -v cargo >/dev/null || { echo 'Instale Rust e adicione cargo ao PATH.' >&2; exit 1; }
rustup run 1.90.0 rustc --version
mkdir -p "$project_root/build"
if [[ ! -d "$oxide_source/.git" ]]; then
    git clone --no-checkout https://github.com/T-x-T/oxide.git "$oxide_source"
    git -C "$oxide_source" checkout --detach "$oxide_revision"
fi
if [[ "$(git -C "$oxide_source" rev-parse HEAD)" != "$oxide_revision" ]]; then
    echo "O checkout em $oxide_source deve estar no commit $oxide_revision." >&2
    exit 1
fi
python3 "$project_root/scripts/patch-oxide.py" "$oxide_source"
cargo build --manifest-path "$oxide_source/Cargo.toml" --locked --release --package server --bin server --target aarch64-linux-android
binary="$CARGO_TARGET_DIR/aarch64-linux-android/release/server"
python3 "$project_root/scripts/validate-elf.py" "$binary"
mkdir -p "$project_root/app/src/main/jniLibs/arm64-v8a"
cp "$binary" "$project_root/app/src/main/jniLibs/arm64-v8a/liboxide.so"
echo 'Oxide ARM64 preparado. Execute ./gradlew assembleDebug.'

