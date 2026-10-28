// Standalone types for executing the actual patched constructors and tick functions.
// These isolate server behavior from unavailable third-party dependencies; they are not a server implementation.
#![allow(dead_code)]
extern crate self as rand;
extern crate self as data;
extern crate self as basic_types;
extern crate self as lib;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::cell::Cell;

mod types { include!("NBT_TYPES"); pub mod position { pub use crate::BlockPosition; } }
#[path = "MCOPT_MODULE"] mod mcopt;
#[path = "PATCHED_SERIALIZER"] mod serializer;

pub const RANDOM_TICK_SPEED: u8 = 3;
thread_local! { static DRAW: Cell<usize> = const { Cell::new(0) }; static DISPATCH: Cell<usize> = const { Cell::new(0) }; }
pub trait Rng { fn random_range(&mut self, range: std::ops::Range<i32>) -> i32; }
pub struct Generator;
pub fn rng() -> Generator { Generator }
impl Rng for Generator {
    fn random_range(&mut self, _: std::ops::Range<i32>) -> i32 {
        DRAW.with(|draw| { let i = draw.get(); draw.set(i + 1); [0, 1, 255, 256, 4095, 42, 17][i % 7] })
    }
}
pub mod blocks {
    #[derive(Clone)] pub enum Type { Crop, Carrot, Potato, Beetroot, Farm, Block }
    pub fn get_type_from_block_state_id(id: u16) -> Type {
        match id { 2 => Type::Crop, 3 => Type::Carrot, 4 => Type::Potato, 5 => Type::Beetroot, 6 => Type::Farm, _ => Type::Block }
    }
}
pub struct State { id: u16 }
pub struct Block { states: Vec<State> }
#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct BlockPosition { x: i32, y: i16, z: i32 }
#[derive(Clone)] pub struct ChunkSection { blocks: Vec<u16>, biomes: Vec<u8>, sky_lights: Vec<u8>, block_lights: Vec<u8> }
#[derive(Clone)] pub struct BlockEntity;
impl BlockEntity {
    fn get_position(&self) -> BlockPosition { BlockPosition { x: 0, y: 0, z: 0 } }
    fn remove_self(&self, _: &mut Vec<Player>, _: &mut Dimension, _: &PacketSender, _: &()) {}
}
#[derive(Clone)] pub struct Chunk {
    x: i32, z: i32, sections: Vec<ChunkSection>, last_update: i64, inhabited_time: i64,
    is_light_on: bool, modified: bool, block_entities: Vec<BlockEntity>, keep_loaded_for_ticks: i32,
    heightmap_motion_blocking: Vec<i16>, heightmap_motion_blocking_no_leaves: Vec<i16>,
    heightmap_ocean_floor: Vec<i16>, heightmap_world_surface: Vec<i16>,
}
#[derive(Clone)] pub struct Dimension { name: String, chunks: HashMap<(i32,i32), Chunk>, changes: Vec<(BlockPosition,u16)> }
impl Dimension {
    fn overwrite_block(&mut self, position: BlockPosition, id: u16) -> Result<Option<BlockOverwriteOutcome>, ()> {
        self.changes.push((position,id)); Ok(None)
    }
    fn get_chunk_from_position(&self, _: BlockPosition) -> Option<&Chunk> { self.chunks.values().next() }
}
pub enum BlockOverwriteOutcome { DestroyBlockentity }
pub struct Player;
#[derive(Clone)] pub struct World { dimensions: HashMap<String, Dimension> }
pub struct PacketSender;
impl PacketSender {
    fn send_packet_to_everyone_in_dimension(&self, _: &[Player], _: &str, _: u8, _: packets::clientbound::play::BlockUpdate) {}
}
pub mod packets {
    pub mod clientbound { pub mod play {
        pub struct BlockUpdate { pub location: crate::BlockPosition, pub block_id: i32 }
        impl BlockUpdate { pub const PACKET_ID: u8 = 1; }
    } }
}
pub struct Game { world: Mutex<World>, players: Mutex<Vec<Player>>, block_state_data: HashMap<String, Block>, packet_sender: PacketSender, entity_id_manager: () }
pub mod block {
    pub fn tick(id: u16, _: &crate::Dimension, _: crate::BlockPosition, _: &std::collections::HashMap<String,crate::Block>) -> u16 {
        crate::DISPATCH.with(|count| count.set(count.get()+1));
        if (2..=6).contains(&id) { id + 10 } else { id }
    }
}

fn run(world: World, tick: fn(Arc<Game>, &[Player])) -> (Vec<(BlockPosition,u16)>, usize, usize) {
    let game = Arc::new(Game { world: Mutex::new(world), players: Mutex::new(vec![]),
        block_state_data: (0..=6).map(|id| (id.to_string(), Block { states: vec![State { id }] })).collect(),
        packet_sender: PacketSender, entity_id_manager: () });
    DRAW.with(|draw| draw.set(0)); DISPATCH.with(|count| count.set(0));
    tick(game.clone(), &[]);
    let mut changes: Vec<_> = game.world.lock().unwrap().dimensions.values().flat_map(|d| d.changes.clone()).collect();
    changes.sort_by_key(|(p,id)| (p.x,p.y,p.z,*id));
    (changes, DRAW.with(Cell::get), DISPATCH.with(Cell::get))
}

#[test]
fn random_ticks_preserve_draws_and_updates_while_skipping_inert_dispatches() {
    let mut chunk = Chunk::new(0,0,24);
    chunk.sections[0].blocks = (0..4096).map(|i| (i % 7) as u16).collect();
    chunk.sections[1].blocks = vec![1; 4096];
    chunk.sections[2].blocks.clear();
    let mut unloaded = chunk.clone(); unloaded.keep_loaded_for_ticks = 0;
    let world = World { dimensions: HashMap::from([("overworld".into(), Dimension { name: "overworld".into(),
        chunks: HashMap::from([((0,0),chunk),((1,0),unloaded)]), changes: vec![] })]) };
    let old = run(world.clone(), reference_tick::process);
    let new = run(world, fast_tick::process);
    assert_eq!(old.0,new.0);
    assert_eq!(old.1,new.1);
    assert!(new.2 < old.2);
}

#[test]
fn patched_chunks_match_heightmaps_and_light_for_optional_terrain() {
    let flat = Chunk::new(0,0,24);
    assert_eq!(flat.heightmap_world_surface, vec![-49;256]);
    let root = std::env::temp_dir().join(format!("oxide-constructor-test-{}",std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    mcopt::terrain::world_seed(&root,Some("42")).unwrap();
    mcopt::terrain::configure(&root).unwrap();
    for (x,z) in [(0,0),(-1,-1),(1,0)] {
        let chunk = Chunk::new(x,z,24);
        let heights = mcopt::terrain::heights(x,z).unwrap();
        assert_eq!(chunk.heightmap_world_surface,heights);
        assert_eq!(chunk.heightmap_motion_blocking,heights);
        for column in 0..256 {
            for y in -64..=-33 {
                let section = &chunk.sections[((y+64)/16) as usize];
                let index = ((y+64)%16) as usize * 256 + column;
                assert_eq!(section.blocks[index],if y<=heights[column] {1} else {0});
                let light = (section.sky_lights[index/2] >> ((index&1)*4)) & 15;
                assert_eq!(light,if y<=heights[column] {0} else {15});
            }
        }
    }
    let nether = Chunk::new(0,0,16);
    assert_eq!(nether.heightmap_world_surface,vec![15;256]);
    std::fs::remove_dir_all(root).unwrap();
}
