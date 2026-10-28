//! Optional low hills for the existing stone test world; not vanilla world generation.
#[path = "vendor/noise.rs"]
mod noise;
use std::io;
use std::path::Path;
use std::sync::OnceLock;

static TERRAIN: OnceLock<Option<noise::Sampler>> = OnceLock::new();

pub fn configure(world: &Path) -> io::Result<()> {
    let requested = std::env::var("OXIDE_MCOPT_SEED").ok();
    let seed = world_seed(world, requested.as_deref())?;
    TERRAIN.set(seed.map(noise::Sampler::new)).map_err(|_| io::Error::other("terrain already configured"))
}

pub fn world_seed(world: &Path, requested: Option<&str>) -> io::Result<Option<u64>> {
    let parse = |value: &str| value.trim().parse::<u64>().map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "OXIDE_MCOPT_SEED must be a u64"));
    let requested = requested.map(parse).transpose()?;
    let marker = world.join("mcopt-terrain-v1.seed");
    match std::fs::read_to_string(&marker) {
        Ok(value) => {
            let saved = parse(&value)?;
            if requested.is_some_and(|seed| seed != saved) {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "cannot change an existing world's mcopt seed"));
            }
            Ok(Some(saved))
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            let Some(seed) = requested else { return Ok(None) };
            // Any existing content may belong to a flat/imported world, even without level.dat.
            if world.exists() && std::fs::read_dir(world)?.next().is_some() {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "mcopt terrain requires a new empty world"));
            }
            std::fs::create_dir_all(world)?;
            super::region::replace_file(&marker, format!("{seed}\n").as_bytes())?;
            Ok(Some(seed))
        }
        Err(error) => Err(error),
    }
}

pub fn heights(chunk_x: i32, chunk_z: i32) -> Option<[i16; 256]> {
    TERRAIN.get()?.as_ref().map(|sampler| sampler.heights(chunk_x, chunk_z))
}

pub fn spawn_y() -> i16 { heights(0, 0).map(|heights| heights[0] + 1).unwrap_or(-48) }

