#!/usr/bin/env python3
"""Execute the real patched constructors and random-tick source with standalone test types."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("patch_oxide", ROOT / "scripts/patch-oxide.py")
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)
output = ROOT / "build/mcopt-tests/integration"
shutil.copytree(ROOT / "tests/fixtures/oxide", output, dirs_exist_ok=True)
shutil.copyfile(output / "serialize.rs", output / "lib/src/serialize.rs")
PATCH.patch(output)
source = (ROOT / "tests/integration_harness.rs").read_text()
for name, path in {
    "NBT_TYPES": ROOT / "tests/fixtures/oxide/nbt_types.rs",
    "MCOPT_MODULE": ROOT / "native/mcopt/mod.rs",
    "PATCHED_SERIALIZER": output / "lib/src/serialize.rs",
}.items():
    source = source.replace(name, str(path))
world = (output / "lib/src/types/world/mod.rs").read_text()
start = world.index("impl Chunk {\n\tpub fn new(")
end = world.index("\n\tfn set_block(", start)
source += "\n" + world[start:end] + "\n}\n"
reference = ROOT / "tests/fixtures/oxide/server/src/tickloop/random_tick.rs"
patched = output / "server/src/tickloop/random_tick.rs"
source += "\nmod reference_tick {\n" + reference.read_text() + "\n}\n"
source += "\nmod fast_tick {\n" + patched.read_text() + "\n}\n"
harness = output / "harness.rs"
harness.write_text(source)
subprocess.run([sys.argv[1], "--edition", "2024", "--test", str(harness), "-o", str(output / "test")], check=True)
# The constructor test configures global terrain; serialize execution to keep fixture setup deterministic.
environment = dict(os.environ)
environment.pop("OXIDE_MCOPT_SEED", None)
subprocess.run([str(output / "test"), "--test-threads=1"], check=True, env=environment)
