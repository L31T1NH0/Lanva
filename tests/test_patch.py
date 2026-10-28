import importlib.util
from pathlib import Path
import tempfile
import shutil
import subprocess
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("patch_oxide", ROOT / "scripts/patch-oxide.py")
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)


class PatchTest(unittest.TestCase):
    def test_partitioned_states_preserve_values_and_invalid_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "data/blocks/get_block_state_from_block_state_id/src/lib.rs"
            file.parent.mkdir(parents=True)
            original = '''use basic_types::blocks::*;
pub fn get_block_state_from_block_state_id(block_state_id: u16) -> State {
\treturn match block_state_id {
'''
            for state_id in (0, 255, 256, 65535):
                original += f"\t\t{state_id} => State {{id: {state_id}, properties: vec![{state_id}]}},\n"
            original += '\t\t_ => panic!("block_state_id {} doesnt exist", block_state_id)\n\t}\n}\n'
            file.write_text(original)
            PATCH.patch_block_states(root)
            patched = file.read_text()
            PATCH.patch_block_states(root)
            self.assertEqual(patched, file.read_text())
            harness = root / "check.rs"
            harness.write_text('''mod basic_types { pub mod blocks {
    #[derive(Debug, PartialEq)] pub struct State { pub id: u16, pub properties: Vec<u16> }
} }
''' + "mod original { use crate::basic_types;\n" + original + "}\n"
                + "mod patched { use crate::basic_types;\n" + patched + "}\n" + '''fn main() {
    std::panic::set_hook(Box::new(|_| {}));
    for id in 0..=u16::MAX {
        let before = std::panic::catch_unwind(|| original::get_block_state_from_block_state_id(id));
        let after = std::panic::catch_unwind(|| patched::get_block_state_from_block_state_id(id));
        match (before, after) {
            (Ok(before), Ok(after)) => assert_eq!(before, after),
            (Err(_), Err(_)) => {},
            _ => panic!("changed behavior for {id}"),
        }
    }
}
''')
            compiler = shutil.which("rustc") or str(Path.home() / ".cargo/bin/rustc")
            subprocess.run([compiler, "--edition=2024", "-C", "opt-level=3", str(harness), "-o", str(root / "check")],
                           check=True, capture_output=True)
            subprocess.run([str(root / "check")], check=True, capture_output=True)

    def test_patch_is_repeatable_and_saves_between_ticks(self):
        # These anchors are from the pinned upstream commit; the full source compiles in CI.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / "tests/fixtures/oxide", root, dirs_exist_ok=True)
            shutil.copyfile(root / "serialize.rs", root / "lib/src/serialize.rs")
            sources = [str(path.relative_to(root)) for path in root.rglob("*.rs")]
            PATCH.patch(root)
            once = {path: (root / path).read_text() for path in sources}
            PATCH.patch(root)
            self.assertEqual(once, {path: (root / path).read_text() for path in sources})
            main = once["server/src/main.rs"]
            self.assertLess(main.index("game.save_all()"), main.index('println!("OXIDE_MOBILE_SAVED")'))
            self.assertLess(main.index("game.save_all()"), main.index("std::process::exit(0)"))
            self.assertLess(main.index('println!("OXIDE_MOBILE_READY")'), main.index("for stream in listener.incoming()"))
            self.assertIn('bytes == 0 || input.trim() == "/oxide-mobile-stop"', once["server/src/terminal_input.rs"])
            self.assertIn("region_changed", once["lib/src/types/world/loader/vanilla.rs"])
            self.assertEqual(once["lib/src/types/world/loader/vanilla.rs"].count("region::replace_file"), 2)
            self.assertIn("nbt::network(input)", once["lib/src/serialize.rs"])
            self.assertIn("!ticking[block_id_to_tick as usize]", once["server/src/tickloop/random_tick.rs"])
            crafting = once["server/src/tickloop/packet_handler_actions/click_container.rs"]
            self.assertNotIn(".as_array()", crafting)
            states_path = "data/blocks/get_block_state_from_block_state_id/src/lib.rs"
            self.assertIn("match block_state_id / 256", once[states_path])
            self.assertIn("#[inline(never)]", once[states_path])
            self.assertIn("256 => State", once[states_path])
            self.assertTrue((root / "lib/src/mcopt/vendor/NOTICE").is_file())

    def test_incompatible_source_fails_instead_of_silently_building(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "file.rs"
            file.write_text("different source")
            with self.assertRaises(RuntimeError):
                PATCH.replace_once(Path(directory), "file.rs", "expected", "patched")

    def test_android_manifest_keeps_service_private_and_packages_binary(self):
        android = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        permissions = {item.get(android + "name") for item in manifest.findall("uses-permission")}
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", permissions)
        app = manifest.find("application")
        self.assertEqual(app.get(android + "extractNativeLibs"), "true")
        self.assertEqual(app.find("service").get(android + "exported"), "false")
        self.assertEqual(app.find("service").get(android + "foregroundServiceType"), "specialUse")
        for file in (ROOT / "app/src/main/res").rglob("*.xml"):
            ET.parse(file)


if __name__ == "__main__":
    unittest.main()
