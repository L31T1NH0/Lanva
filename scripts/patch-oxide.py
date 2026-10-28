#!/usr/bin/env python3
"""Small, idempotent Android adaptations for the pinned Oxide 0.9.0 source."""
from pathlib import Path
import re
import shutil
import sys


def replace_once(root, path, before, after):
    file = root / path
    source = file.read_text()
    if after in source:
        return
    if source.count(before) != 1:
        raise RuntimeError(f"Fonte do Oxide incompatível: {path}")
    file.write_text(source.replace(before, after, 1))


def patch(root):
    replace_once(root, "server/src/main.rs", "fn main() {", """static MOBILE_STOP_REQUESTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn main() {""")
    replace_once(root, "server/src/main.rs", "\tfor stream in listener.incoming() {", """\tprintln!("OXIDE_MOBILE_READY");
\tstd::io::stdout().flush().unwrap();

\tfor stream in listener.incoming() {""")
    # Save on the tick thread, between ticks, so the world stops changing before the final save.
    replace_once(root, "server/src/main.rs", "\t\tlet start_time = std::time::Instant::now();", """\t\tif MOBILE_STOP_REQUESTED.load(std::sync::atomic::Ordering::Acquire) {
\t\t\tgame.save_all();
\t\t\tprintln!("OXIDE_MOBILE_SAVED");
\t\t\tstd::io::stdout().flush().unwrap();
\t\t\tstd::process::exit(0);
\t\t}
\t\tlet start_time = std::time::Instant::now();""")
    replace_once(root, "server/src/terminal_input.rs", "\t\t\tstd::io::stdin().read_line(&mut input).unwrap();", """\t\t\tlet bytes = std::io::stdin().read_line(&mut input).unwrap();
\t\t\tif bytes == 0 || input.trim() == "/oxide-mobile-stop" {
\t\t\t\tcrate::MOBILE_STOP_REQUESTED.store(true, std::sync::atomic::Ordering::Release);
\t\t\t\treturn;
\t\t\t}""")
    replace_once(root, "lib/src/lib.rs", "pub const SPAWN_CHUNK_RADIUS: i8 = 10;", "pub const SPAWN_CHUNK_RADIUS: i8 = 2;")
    replace_once(root, "lib/src/lib.rs", "pub const VIEW_DISTANCE: i8 = 10;", "pub const VIEW_DISTANCE: i8 = 4;")
    patch_rust_compat(root)
    patch_block_states(root)
    patch_mcopt(root)


def replace_text(source, before, after, count=1):
    if source.count(before) != count:
        raise RuntimeError(f"Fonte do Oxide incompatível: {before[:80]!r}")
    return source.replace(before, after)


def patch_rust_compat(root):
    # slice::as_array is unstable in the pinned Rust 1.90 toolchain.
    file = root / "server/src/tickloop/packet_handler_actions/click_container.rs"
    source = file.read_text()
    if ".as_array()" not in source:
        return
    source = replace_text(source, "crafting_slots.as_array()", "crafting_slots.as_slice().try_into()", count=2)
    source = replace_text(source, "items.as_array().unwrap().clone()",
                          "<&[Slot; 9]>::try_from(items.as_slice()).unwrap().clone()")
    source = replace_text(source, "collect::<Vec<Slot>>()\n\t\t\t\t.as_array()\n\t\t\t\t.unwrap()\n\t\t\t\t.clone()",
                          "collect::<Vec<Slot>>()\n\t\t\t\t.try_into()\n\t\t\t\t.unwrap()")
    source = replace_text(source, ".as_array()", ".as_slice().try_into()", count=2)
    file.write_text(source)


def patch_block_states(root):
    file = root / "data/blocks/get_block_state_from_block_state_id/src/lib.rs"
    source = file.read_text()
    if "// MOBILE_BLOCK_STATES_V1" in source:
        return
    header = "use basic_types::blocks::*;\npub fn get_block_state_from_block_state_id(block_state_id: u16) -> State {\n\treturn match block_state_id {\n"
    fallback = '\t\t_ => panic!("block_state_id {} doesnt exist", block_state_id)'
    footer = fallback + "\n\t}\n}\n"
    if not source.startswith(header) or not source.endswith(footer):
        raise RuntimeError("Tabela de estados do Oxide incompatível")
    groups = {}
    ids = set()
    for line in source[len(header):-len(footer)].splitlines():
        match = re.fullmatch(r"\t\t(\d+) => State \{.*\},", line)
        if match is None or int(match[1]) in ids or int(match[1]) > 65535:
            raise RuntimeError("Estado de bloco do Oxide incompatível")
        state_id = int(match[1])
        ids.add(state_id)
        groups.setdefault(state_id // 256, []).append(line)
    # Keep each optimized function small; the original 30k-arm match exhausts compiler memory.
    output = "// MOBILE_BLOCK_STATES_V1\n" + header.replace("match block_state_id {", "match block_state_id / 256 {")
    for group in sorted(groups):
        output += f"\t\t{group} => block_states_{group}(block_state_id),\n"
    output += footer
    for group in sorted(groups):
        output += f"\n#[inline(never)]\nfn block_states_{group}(block_state_id: u16) -> State {{\n\tmatch block_state_id {{\n"
        output += "\n".join(groups[group]) + "\n" + footer
    file.write_text(output)


def patch_mcopt(root):
    modules = Path(__file__).resolve().parents[1] / "native/mcopt"
    shutil.copytree(modules, root / "lib/src/mcopt", dirs_exist_ok=True)
    replace_once(root, "lib/src/lib.rs", "pub mod utils;", "pub mod utils;\npub mod mcopt;")
    replace_once(root, "server/src/main.rs", "fn initialize_server() {", '''fn initialize_server() {
\tlet world_path = PathBuf::from(std::env::var("OXIDE_WORLD_PATH").unwrap_or_else(|_| "./world".to_string()));
\tlib::mcopt::terrain::configure(&world_path).expect("invalid mcopt world configuration");''')
    replace_once(root, "lib/src/types/world/mod.rs", "\t\t\t\ty: -48,", "\t\t\t\ty: crate::mcopt::terrain::spawn_y(),")
    replace_once(root, "lib/src/types/world/mod.rs", "\t\tlet empty_chunk_sections = vec![", "\t\tlet mut empty_chunk_sections = vec![")
    replace_once(root, "lib/src/types/world/mod.rs", '''\t\tlet mut all_chunk_sections = filled_chunk_sections.clone();
\t\tall_chunk_sections.append(&mut empty_chunk_sections.clone());''', '''\t\tlet mut all_chunk_sections = filled_chunk_sections;
\t\tall_chunk_sections.append(&mut empty_chunk_sections);''')
    replace_once(root, "lib/src/types/world/mod.rs", '''\t\tlet heightmap = if chunk_sections == 24 { [-49; 256] } else { [15; 256] };''', '''\t\tlet mut heightmap = if chunk_sections == 24 { [-49; 256] } else { [15; 256] };
\t\tif chunk_sections == 24 {
\t\t\tif let Some(heights) = crate::mcopt::terrain::heights(chunk_x, chunk_z) {
\t\t\t\theightmap = heights;
\t\t\t\tfor (section_index, section) in all_chunk_sections.iter_mut().take(2).enumerate() {
\t\t\t\t\tfor index in 0..4096 {
\t\t\t\t\t\tlet column = index & 255;
\t\t\t\t\t\tlet y = -64 + (section_index * 16 + index / 256) as i16;
\t\t\t\t\t\tlet solid = y <= heights[column];
\t\t\t\t\t\tsection.blocks[index] = if solid { 1 } else { 0 };
\t\t\t\t\t\tlet shift = (index & 1) * 4;
\t\t\t\t\t\tlet light = if solid { 0 } else { 15 };
\t\t\t\t\t\tsection.sky_lights[index / 2] = (section.sky_lights[index / 2] & !(15 << shift)) | (light << shift);
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t}''')

    serializer = root / "lib/src/serialize.rs"
    source = serializer.read_text()
    if "// MCOPT_NBT_V1" not in source:
        start = source.index("pub fn nbt_network(")
        end = source.index("#[cfg(test)]", start)
        source = source[:start] + '''// MCOPT_NBT_V1: one buffer per NBT value, preserving the original wire representation.
pub fn nbt_network(input: NbtTag) -> Vec<u8> { crate::mcopt::nbt::network(input) }
pub fn nbt_disk(input: NbtTag) -> Vec<u8> { crate::mcopt::nbt::disk(input) }

''' + source[end:]
        serializer.write_text(source)

    ticks = root / "server/src/tickloop/random_tick.rs"
    source = ticks.read_text()
    if "// MCOPT_TICKS_V1" not in source:
        source = replace_text(source, "\tfor dimension in world.dimensions.values_mut() {", '''\t// MCOPT_TICKS_V1: only these states can change in the pinned Oxide's dispatcher.
\tstatic TICKING: std::sync::OnceLock<Vec<bool>> = std::sync::OnceLock::new();
\tlet ticking = TICKING.get_or_init(|| {
\t\tlet mut states = vec![false; 65536];
\t\tfor block in game.block_state_data.values() {
\t\t\tfor state in &block.states {
\t\t\t\tstates[state.id as usize] = matches!(data::blocks::get_type_from_block_state_id(state.id),
\t\t\t\t\tbasic_types::blocks::Type::Crop | basic_types::blocks::Type::Carrot |
\t\t\t\t\tbasic_types::blocks::Type::Potato | basic_types::blocks::Type::Beetroot | basic_types::blocks::Type::Farm);
\t\t\t}
\t\t}
\t\tstates
\t});
\tlet mut rng = rand::rng();
\tfor dimension in world.dimensions.values_mut() {''')
        source = replace_text(source, "\t\t\t\tlet mut rng = rand::rng();", "\t\t\t\tif chunk.sections[chunk_section_index].blocks.is_empty() { continue; }")
        source = replace_text(source, '''\t\t\t\t\tif chunk.sections[chunk_section_index].blocks.is_empty() {
\t\t\t\t\t\tbreak;
\t\t\t\t\t}
''', "")
        source = replace_text(source, "\t\t\t\t\tlet x: i32 =", "\t\t\t\t\tif !ticking[block_id_to_tick as usize] { continue; }\n\n\t\t\t\t\tlet x: i32 =")
        ticks.write_text(source)

    loader = root / "lib/src/types/world/loader/vanilla.rs"
    source = loader.read_text()
    if "// MCOPT_REGION_V1" not in source:
        start = source.index("fn save_region_to_disk(")
        prefix, region = source[:start], source[start:]
        region = replace_text(region, "\tfor chunk in chunks {", '''\t// MCOPT_REGION_V1: retain capacity across chunks and skip exact on-disk matches.
\tlet mut uncompressed_chunk = Vec::new();
\tlet mut region_changed = false;
\tfor chunk in chunks {''')
        region = replace_text(region, "\t\tlet mut uncompressed_chunk = crate::serialize::nbt_disk(chunk_nbt);", '''\t\tcrate::mcopt::nbt::disk_into(chunk_nbt, &mut uncompressed_chunk);
\t\tlet chunk_index = ((chunk.x & 31) + (chunk.z & 31) * 32) as usize;
\t\tif let Some(stored) = chunk_data[chunk_index].as_deref() {
\t\t\tif let Some(payload) = crate::mcopt::region::zlib_payload(stored) {
\t\t\t\tif crate::mcopt::region::identical(&uncompressed_chunk, ZlibDecoder::new(payload)).unwrap_or(false) {
\t\t\t\t\tcontinue;
\t\t\t\t}
\t\t\t}
\t\t}
\t\tregion_changed = true;''')
        region = replace_text(region, "\tlet mut first_chunk = true;", "\tif !region_changed { return; }\n\n\tlet mut first_chunk = true;")
        region = replace_text(region,
            "let biome_palette: Vec<u8> = section.biomes.iter().copied().collect::<HashSet<u8>>().into_iter().collect();",
            "let mut biome_palette: Vec<u8> = section.biomes.iter().copied().collect::<HashSet<u8>>().into_iter().collect();\n\t\t\t\t\t\tbiome_palette.sort_unstable();")
        region = replace_text(region, "let block_palette: Vec<u16> =", "let mut block_palette: Vec<u16> =")
        region = replace_text(region, "\t\t\t\t\t\tlet blocks_bits_per_entry =", "\t\t\t\t\t\tblock_palette.sort_unstable();\n\t\t\t\t\t\tlet blocks_bits_per_entry =")
        source = prefix + region
        source = replace_text(source, "\tlet mut file = OpenOptions::new().read(true).write(true).truncate(true).create(true).open(region_file_path).unwrap();", "", count=2)
        source = replace_text(source, "\tfile.write_all(&output).unwrap();\n\tfile.flush().unwrap();", "\tcrate::mcopt::region::replace_file(&region_file_path, &output).unwrap();", count=2)
        loader.write_text(source)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Uso: patch-oxide.py CAMINHO_DO_OXIDE")
    patch(Path(sys.argv[1]))

