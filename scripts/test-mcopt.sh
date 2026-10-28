#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
rust_compiler="${RUSTC:-rustc}"
if ! command -v "$rust_compiler" >/dev/null && [[ -x "$HOME/.cargo/bin/rustc" ]]; then
    rust_compiler="$HOME/.cargo/bin/rustc"
fi
mkdir -p "$project_root/build/mcopt-tests"
"$rust_compiler" --edition 2024 --test "$project_root/tests/mcopt_tests.rs" -o "$project_root/build/mcopt-tests/server-tests"
env -u OXIDE_MCOPT_SEED "$project_root/build/mcopt-tests/server-tests"
"$rust_compiler" --edition 2024 --crate-type cdylib -C opt-level=2 "$project_root/native/mcopt/vendor/noise.rs" -o "$project_root/build/mcopt-tests/libnoise.so"
python3 "$project_root/tests/check-noise.py" --library "$project_root/build/mcopt-tests/libnoise.so"
python3 "$project_root/tests/check-integration.py" "$rust_compiler"
